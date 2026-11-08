#!/usr/bin/env python3
"""Build and compare the optimized renderer against an original siliconshaders JAR on macOS/Apple Silicon.
Requires JDK 25 (JAVA_HOME) and Apple's Command Line Tools. Does not launch Minecraft or install the mod.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import subprocess
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('original_jar', type=Path)
    args = parser.parse_args()
    if platform.system() != 'Darwin' or platform.machine() != 'arm64':
        parser.error('Native verification requires macOS on Apple Silicon.')
    root = Path(__file__).resolve().parents[1]
    output = root / 'metal/build/optimization-verification'
    output.mkdir(parents=True, exist_ok=True)
    reference = output / 'reference'
    with zipfile.ZipFile(args.original_jar.resolve()) as archive:
        for name in ['siliconshaders/own/terrain.metal', 'siliconshaders/own/sun.metal', 'natives/libsiliconshaders.dylib']:
            dest = reference / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(archive.read(name))
    with (output / 'build.log').open('w') as log:
        subprocess.run([str(root / 'gradlew'), ':metal:build', ':metal:waterChecks', ':metal:surfaceMaterialChecks', '--console=plain'], cwd=root, stdout=log, stderr=subprocess.STDOUT, check=True)
    native = root / 'metal/src/main/native'
    tests = root / 'metal/src/test/native'
    library = root / 'metal/build/natives/natives'
    shaders = root / 'metal/src/main/resources/siliconshaders/own'
    shader_args = [str(shaders / 'terrain.metal'), str(shaders / 'sun.metal')]
    cases = [
        ('water', 'water_check', [str(output / 'water-images')], {}),
        ('water-readable', 'water_check', [str(output / 'readable-images')], {'SILICONSHADERS_WATER_READABLE_DEPTH': '1', 'MTL_DEBUG_LAYER': '1'}),
        ('water-optics', 'water_optics_check', [], {}),
        ('water-fov', 'water_fov_check', [], {}),
        ('water-reflection', 'water_reflection_check', [], {}),
        ('water-steps', 'water_reflection_steps_check', [], {}),
        ('water-fast', 'water_refraction_fast_check', [], {}),
        ('materials', 'surface_material_check', [], {}),
        ('transparent', 'translucent_material_check', [str(output / 'transparent-images')], {}),
        ('reflection-fog', 'reflection_fog_cull_check', [], {}),
        ('upscale', 'upscale_check', [], {}),
        ('ssao', 'ssao_check', [str(shaders / 'ssao.metal'), str(output / 'ssao-images')], {}),
        ('receiver-grid', 'sun_receiver_grid_check', [], {}),
        ('temporal', 'sun_temporal_check', [], {}),
        ('equivalence', 'optimization_equivalence_check', [str(reference / 'siliconshaders/own/terrain.metal'), str(reference / 'siliconshaders/own/sun.metal')], {}),
    ]
    for folder in ['water-images', 'readable-images', 'transparent-images', 'ssao-images']:
        (output / folder).mkdir(exist_ok=True)
    results = []
    compiled = set()
    for label, binary, extra, env in cases:
        if binary not in compiled:
            cmd = ['clang', '-fno-objc-arc', '-O2', '-arch', 'arm64', '-mmacosx-version-min=15.0', '-I', str(native), '-I', str(tests), str(tests / (binary + '.m')), '-L', str(library), '-lmcmetal', '-Wl,-rpath,' + str(library), '-o', str(output / binary)]
            for framework in ['Foundation', 'Metal', 'MetalFX', 'QuartzCore', 'CoreGraphics', 'ImageIO']:
                cmd += ['-framework', framework]
            with (output / (binary + '.compile.log')).open('w') as log:
                subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, check=True)
            compiled.add(binary)
        with (output / (label + '.log')).open('w') as log:
            p = subprocess.run([str(output / binary), *shader_args, *extra], stdout=log, stderr=subprocess.STDOUT, env={**os.environ, **env})
        results.append({'name': label, 'exit_code': p.returncode})
        print(label, 'PASS' if p.returncode == 0 else 'FAIL', flush=True)
    (output / 'results.json').write_text(json.dumps(results, indent=2) + '\n')
    print('Logs:', output)
    return int(any(result['exit_code'] for result in results))


if __name__ == '__main__':
    raise SystemExit(main())
