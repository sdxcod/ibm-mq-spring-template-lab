import json, os, subprocess, tempfile, time, unittest, urllib.request, urllib.error, socket
from pathlib import Path
class MockApiTest(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory()
        with socket.socket() as sock:
            sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
        self.base=f'http://127.0.0.1:{port}'
        env=dict(os.environ,MOCK_PORT=str(port),MOCK_BIND='127.0.0.1',MOCK_STATE_FILE=str(Path(self.temp.name)/'settings.json'),MOCK_FIXTURE='mock/queue-settings.json')
        self.process=subprocess.Popen(['python3','mock/server.py'],env=env,stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
        for _ in range(100):
            try:self.call('/health');break
            except Exception:time.sleep(0.01)
        else:self.fail('mock server did not start')
    def tearDown(self):
        self.process.terminate();self.process.wait(timeout=3);self.temp.cleanup()
    def call(self,path,method='GET',body=None):
        request=urllib.request.Request(self.base+path,data=None if body is None else json.dumps(body).encode(),method=method,headers={'Content-Type':'application/json'})
        with urllib.request.urlopen(request,timeout=2) as response:return json.load(response)
    def test_patch_increments_revision_and_container_view_translates_only_local_host(self):
        original=self.call('/queue-settings')
        patched=self.call('/queue-settings','PATCH',{'enabled':False,'inward-batch-count':2})
        self.assertEqual(original['revision']+1,patched['revision']);self.assertFalse(patched['enabled'])
        self.assertEqual('mq',self.call('/queue-settings/container')['host'])
        self.call('/queue-settings','PATCH',{'host':'unreachable.invalid'})
        self.assertEqual('unreachable.invalid',self.call('/queue-settings/container')['host'])
    def test_http_fault_recovery_and_reset_preserve_monotonic_revision(self):
        changed=self.call('/queue-settings','PATCH',{'port':1})
        self.call('/control','POST',{'status':503})
        with self.assertRaises(urllib.error.HTTPError) as raised:self.call('/queue-settings')
        self.assertEqual(503,raised.exception.code)
        self.call('/control','POST',{'status':200});self.assertEqual(1,self.call('/queue-settings')['port'])
        reset=self.call('/reset','POST');self.assertGreater(reset['revision'],changed['revision']);self.assertEqual(1414,reset['port'])
    def test_revision_cannot_be_overwritten_and_unknown_patch_is_rejected(self):
        for body in [{'revision':1},{'unknown-setting':True}]:
            with self.assertRaises(urllib.error.HTTPError) as raised:self.call('/queue-settings','PATCH',body)
            self.assertEqual(400,raised.exception.code)
if __name__=='__main__':unittest.main()
