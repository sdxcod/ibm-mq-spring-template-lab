#!/usr/bin/env python3
"""Local mock settings API. Administrative endpoints are unauthenticated lab controls."""
import json, os, time, threading
from pathlib import Path
from http.server import ThreadingHTTPServer, BaseHTTPRequestHandler
STATE=Path(os.environ.get("MOCK_STATE_FILE","mock/state/queue-settings.json"))
FIXTURE=Path(os.environ.get("MOCK_FIXTURE","mock/queue-settings.json"))
LOCK=threading.Lock()
CONTROL={"status":200,"delay-ms":0}
def save(value):
    STATE.parent.mkdir(parents=True,exist_ok=True)
    tmp=STATE.with_suffix(".tmp");tmp.write_text(json.dumps(value,indent=2));tmp.replace(STATE)
if not STATE.exists(): save(json.loads(FIXTURE.read_text()))
class Handler(BaseHTTPRequestHandler):
    def log_message(self,fmt,*args): pass
    def reply(self,status,value):
        data=json.dumps(value).encode();self.send_response(status);self.send_header("Content-Type","application/json");self.send_header("Content-Length",str(len(data)));self.end_headers()
        try: self.wfile.write(data)
        except (BrokenPipeError,ConnectionResetError): pass
    def body(self):
        length=int(self.headers.get("Content-Length","0"))
        if length<1 or length>16384: raise ValueError("Invalid body size")
        value=json.loads(self.rfile.read(length))
        if not isinstance(value,dict): raise ValueError("Expected JSON object")
        return value
    def do_GET(self):
        if self.path=="/health": self.reply(200,{"status":"up"});return
        if self.path not in ("/queue-settings","/queue-settings/container"): self.reply(404,{"error":"not found"});return
        with LOCK: value=json.loads(STATE.read_text()); control=dict(CONTROL)
        time.sleep(control["delay-ms"]/1000)
        if control["status"]!=200: self.reply(control["status"],{"error":"simulated settings failure"});return
        if self.path.endswith("/container") and value.get("host") in ("localhost","127.0.0.1"):
            value["host"]=os.environ.get("MOCK_CONTAINER_MQ_HOST","mq")
        self.reply(200,value)
    def do_PATCH(self):
        if self.path!="/queue-settings": self.reply(404,{"error":"not found"});return
        try:
            patch=self.body()
            with LOCK:
                value=json.loads(STATE.read_text())
                if "revision" in patch or set(patch)-set(value): raise ValueError("Patch known fields only; revision increments automatically")
                value.update(patch);value["revision"]+=1;save(value)
            self.reply(200,value)
        except (ValueError,TypeError,OSError) as error: self.reply(400,{"error":str(error)})
    def do_POST(self):
        try:
            if self.path=="/control":
                value=self.body();status=value.get("status",200);delay=value.get("delay-ms",0)
                if status not in (200,503) or not isinstance(delay,int) or not 0<=delay<=20000: raise ValueError("Invalid control")
                with LOCK: CONTROL.update({"status":status,"delay-ms":delay})
                self.reply(200,dict(CONTROL))
            elif self.path=="/reset":
                with LOCK:
                    revision=json.loads(STATE.read_text())["revision"]+1
                    value=json.loads(FIXTURE.read_text());value["revision"]=revision;save(value);CONTROL.update({"status":200,"delay-ms":0})
                self.reply(200,value)
            else: self.reply(404,{"error":"not found"})
        except (ValueError,TypeError,OSError) as error: self.reply(400,{"error":str(error)})
if __name__=="__main__":
    ThreadingHTTPServer((os.environ.get("MOCK_BIND","127.0.0.1"),int(os.environ.get("MOCK_PORT","8088"))),Handler).serve_forever()
