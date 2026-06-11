# Dora-Pilot

## Native library 16 KB page-size check

Android 15+/Play compatibility requires packaged native libraries to use
16 KB ELF LOAD alignment. Dora's default build excludes the old local
ONNX Runtime GenAI AAR because its bundled native libraries are only
4 KB-aligned. If you enable `DORA_INCLUDE_LOCAL_GENAI_AAR=true`, only place a
16 KB-compatible GenAI AAR in `android/app/libs`.

After building an APK, verify alignment with:

```bash
cd android
python3 scripts/check_elf_alignment.py app/build/outputs/apk/debug/app-debug.apk
```