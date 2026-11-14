"""
disable_sandbox.py - Forces (*RunCommandHandler).isSandboxEnabled to return false (0).
Android kernels lack CONFIG_USER_NS/CONFIG_PID_NS/CONFIG_SECURITY_LANDLOCK, and
launching via libloader.so causes exebox to fork/exec /proc/self/exe (libloader.so)
with namespace cloneflags -> EINVAL ("fork/exec .../libloader.so: invalid argument").
"""
import struct
from typing import Tuple, Dict, Any
from engine import elf_scanner, pclntab_scanner

TARGET_FUNC = "google3/third_party/jetski/cortex/handlers/handlers.(*RunCommandHandler).isSandboxEnabled"
PATCH_BYTES = bytes.fromhex("00008052c0035fd6")  # mov w0, #0; ret


def find_go_text_start(data: bytearray, text_off: int) -> int:
    for o in range(text_off, min(len(data) - 4, text_off + 0x2000), 4):
        if struct.unpack_from("<I", data, o)[0] == 0xF9400B90:
            return o
    return text_off


def apply(data: bytearray, verify: bool = False, dry_run: bool = False, allow_missing: bool = False) -> Tuple[bool, Dict[str, Any]]:
    text_off, _ = elf_scanner.get_text_section(data, 0)
    first_go = find_go_text_start(data, text_off)
    pcln = pclntab_scanner.find_pclntab(data, 0, len(data))
    info: Dict[str, Any] = {
        "name": "disable_sandbox",
        "target_func": TARGET_FUNC,
        "first_go": hex(first_go),
    }
    if not pcln:
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_pclntab"
        return False, info

    res = pclntab_scanner.find_function(data, pcln, TARGET_FUNC)
    if not res:
        if allow_missing:
            info["status"] = "skipped_missing"
            return True, info
        info["status"] = "missing_signature"
        return False, info

    entry_off, func_sz = res
    file_off = first_go + entry_off
    info["file_offset"] = hex(file_off)
    info["func_size"] = func_sz

    cur = bytes(data[file_off:file_off + len(PATCH_BYTES)])
    already = (cur == PATCH_BYTES)

    if verify:
        info["status"] = "verified" if already else "unverified"
        return already, info

    if already:
        info["status"] = "already_patched"
        return True, info

    if not dry_run:
        data[file_off:file_off + len(PATCH_BYTES)] = PATCH_BYTES
        info["status"] = "patched"
    else:
        info["status"] = "dry_run"

    return True, info
