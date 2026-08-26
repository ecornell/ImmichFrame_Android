import csv
import importlib.util
import json
import pathlib
import struct
import subprocess
import sys
import tempfile
import unittest
import zlib


SCRIPTS = pathlib.Path(__file__).parents[1]


def load(name):
    path = SCRIPTS / name
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), path)
    module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    return module


def png(path, width, height, seam=None):
    rows = []
    for y in range(height):
        row = bytearray()
        for x in range(width):
            value = min(255, x * 2)
            if seam is not None and x == seam: value = 255
            row.extend((value, value, value, 255))
        rows.append(b'\0' + bytes(row))
    def chunk(kind, data):
        return struct.pack('>I', len(data)) + kind + data + struct.pack('>I', zlib.crc32(kind + data) & 0xffffffff)
    payload = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', width, height, 8, 6, 0, 0, 0))
    payload += chunk(b'IDAT', zlib.compress(b''.join(rows))) + chunk(b'IEND', b'')
    path.write_bytes(payload)


class ReleaseToolsTest(unittest.TestCase):
    def test_resolver_selects_newest_build_tools(self):
        module = load('resolve-android-tools.py')
        with tempfile.TemporaryDirectory() as directory:
            sdk = pathlib.Path(directory)
            for item in ('platform-tools/adb', 'cmdline-tools/latest/bin/apkanalyzer',
                         'build-tools/35.0.0/apksigner', 'build-tools/36.0.0/apksigner'):
                path = sdk / item; path.parent.mkdir(parents=True, exist_ok=True); path.touch()
            self.assertIn('36.0.0', module.resolve(sdk)['apksigner'])

    def test_seam_analyzer_distinguishes_spike(self):
        module = load('analyze-render-seam.py')
        with tempfile.TemporaryDirectory() as directory:
            smooth, seam = pathlib.Path(directory) / 'smooth.png', pathlib.Path(directory) / 'seam.png'
            png(smooth, 80, 40); png(seam, 80, 40, 12)
            _, _, channels, smooth_rows = module.read_png(smooth)
            _, _, _, seam_rows = module.read_png(seam)
            self.assertLess(module.column_score(smooth_rows, channels, 12), 2)
            self.assertGreater(module.column_score(seam_rows, channels, 12), 50)

    def test_archive_redacts_sensitive_values(self):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory); out = root / 'out'
            metadata = root/'metadata.json'; readiness=root/'readiness.json'; deployment=root/'deployment.json'
            target=root/'target.apk'; rollback=root/'rollback.apk'; target.write_bytes(b'target'); rollback.write_bytes(b'rollback')
            archive=load('archive-release.py')
            metadata.write_text(json.dumps({'releaseId':'v1-a','apkSha256':archive.sha256(target),'url':'secret',
                                             'copiedTo':'/home/person/private/target.apk',
                                             'rollback':{'apkSha256':archive.sha256(rollback)}}))
            readiness.write_text(json.dumps({'releaseId':'v1-a'}))
            deployment.write_text(json.dumps([{'serial':'10.0.0.1:5555','token':'secret'}]))
            result = subprocess.run([sys.executable, str(SCRIPTS/'archive-release.py'), '--metadata', str(metadata),
                '--readiness', str(readiness), '--deployment', str(deployment), '--target-apk', str(target),
                '--rollback-apk', str(rollback), '--output-dir', str(out)],
                check=True, text=True, capture_output=True)
            bundle = pathlib.Path(result.stdout.strip())
            rendered = (bundle/'deployment.json').read_text()
            self.assertNotIn('10.0.0.1', rendered); self.assertNotIn('secret', rendered)
            self.assertNotIn('/home/person', (bundle/'metadata.json').read_text())
            self.assertTrue((bundle/'manifest.json').is_file())

    def test_qualification_binds_release_and_derives_duration(self):
        with tempfile.TemporaryDirectory() as directory:
            root=pathlib.Path(directory); metadata=root/'metadata.json'; history=root/'history.csv'; output=root/'q.json'
            metadata.write_text(json.dumps({'releaseId':'v1-a','apkSha256':'abc'}))
            with history.open('w', newline='') as f:
                writer=csv.DictWriter(f, fieldnames=['timestamp','serial','appPid','resumedApp']); writer.writeheader()
                writer.writerow({'timestamp':'2026-01-01T00:00:00+00:00','serial':'frame:5555','appPid':'','resumedApp':'False'})
                writer.writerow({'timestamp':'2026-01-02T00:00:00+00:00','serial':'frame:5555','appPid':'1','resumedApp':'True'})
            subprocess.run([sys.executable,str(SCRIPTS/'collect-qualification.py'),'--metadata',str(metadata),
                '--history',str(history),'--output',str(output)],check=True,capture_output=True)
            data=json.loads(output.read_text()); self.assertEqual('v1-a',data['releaseId']); self.assertEqual(24,data['soakHours'])


if __name__ == '__main__': unittest.main()
