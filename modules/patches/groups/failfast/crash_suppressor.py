"""
crash_suppressor.py - Bypasses preinit CPU feature check and preinit atomic flag initialization for ARMv8.0-A CPUs.
"""
import struct
from typing import Tuple, Dict, Any, List, Union
from engine import elf_scanner

# 8 instructions: adrp x19, ..; movz w10, #0x8300; ldr x8, [x19, #imm]; mov w9, w8; tst x9, x10; b.ne ..; tbz w8, #7, ..; tbz w8, #10, ..
CPU_PAT = [
    (0x90000013, 0x9f00001f),
    (0x5290600a, 0xffffffff),
    (0xf9400268, 0xffc003ff),
    (0x2a2803e9, 0xffffffff),
    (0xea0a013f, 0xffffffff),
    (0x54000001, 0xff00001f),
    (0x36380008, 0xfff8001f),
    (0x36500008, 0xfff8001f),
]
REPLACEMENT_CPU = bytes.fromhex("190000141f2003d51f2003d51f2003d51f2003d51f2003d51f2003d51f2003d5")

# 7 instructions: adrp x8, ..; add x8, x8, #imm; mov w9, #1; ldaddal x9, x8, [x8]; cbz x8, +8; ret; nop
ATOMIC_PAT = [
    (0x90000008, 0x9f00001f),
    (0x91000108, 0xffc003ff),
    (0x52800029, 0xffffffff),
    (0xf8e90108, 0xffffffff),
    (0xb4000048, 0xffffffff),
    (0xd65f03c0, 0xffffffff),
    (0xd503201f, 0xffffffff),
]
ATOMIC_TAIL_PATCH = bytes.fromhex("090140f92a008052490000b4c0035fd60a0100f9")
ATOMIC_PATCHED_PAT = [
    (0x90000008, 0x9f00001f),
    (0x91000108, 0xffc003ff),
    (0xf9400109, 0xffffffff),
    (0x5280002a, 0xffffffff),
    (0xb4000049, 0xffffffff),
    (0xd65f03c0, 0xffffffff),
    (0xf900010a, 0xffffffff),
]

def _scan_masked(data: Union[bytes, bytearray, memoryview], start: int, end: int, words_and_masks: List[Tuple[int, int]]) -> List[int]:
    anchor_idx, (aval, amask) = max(enumerate(words_and_masks), key=lambda x: bin(x[1][1]).count('1'))
    anchor_bytes = struct.pack('<I', aval)
    assert amask == 0xffffffff
    matches = []
    pos = start
    pat_len = len(words_and_masks) * 4
    while pos < end:
        idx = data.find(anchor_bytes, pos, end)
        if idx == -1:
            break
        base = idx - anchor_idx * 4
        if base >= start and base + pat_len <= end and (base & 3) == 0:
            ok = True
            for i, (val, mask) in enumerate(words_and_masks):
                w = struct.unpack_from('<I', data, base + i * 4)[0]
                if (w & mask) != val:
                    ok = False
                    break
            if ok:
                matches.append(base)
        pos = idx + 4
    return matches

def apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]:
    text_off, text_sz = elf_scanner.get_text_section(data, 0)
    end = text_off + text_sz

    raw_cpu = _scan_masked(data, text_off, end, CPU_PAT)
    repl_cpu = _scan_masked(data, text_off, end, [(struct.unpack_from('<I', REPLACEMENT_CPU, i)[0], 0xffffffff) for i in range(0, len(REPLACEMENT_CPU), 4)])
    raw_atomic = _scan_masked(data, text_off, end, ATOMIC_PAT)
    repl_atomic = _scan_masked(data, text_off, end, ATOMIC_PATCHED_PAT)

    info = {
        "name": "failfast_cpu_and_atomic",
        "cpu_raw": [hex(x) for x in raw_cpu],
        "cpu_patched": [hex(x) for x in repl_cpu],
        "atomic_raw": [hex(x) for x in raw_atomic],
        "atomic_patched": [hex(x) for x in repl_atomic]
    }

    cpu_total = len(raw_cpu) + len(repl_cpu)
    atomic_total = len(raw_atomic) + len(repl_atomic)

    if (cpu_total == 0 or atomic_total == 0):
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_signature"
        return False, info

    if verify:
        v_ok = (len(raw_cpu) == 0 and len(repl_cpu) == 1 and len(raw_atomic) == 0 and len(repl_atomic) == 1)
        info["status"] = "verified" if v_ok else "unverified"
        return v_ok, info

    if len(raw_cpu) == 0 and len(raw_atomic) == 0:
        info["status"] = "already_patched"
        return True, info

    if not dry_run:
        for pos in raw_cpu:
            data[pos:pos + len(REPLACEMENT_CPU)] = REPLACEMENT_CPU
        for pos in raw_atomic:
            data[pos + 8:pos + 8 + len(ATOMIC_TAIL_PATCH)] = ATOMIC_TAIL_PATCH
        info["status"] = "patched"
    else:
        info["status"] = "dry_run"

    return True, info
