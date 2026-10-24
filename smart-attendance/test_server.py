import subprocess, time, json, urllib.request, sys

backend_dir = r"C:\Users\hp\OneDrive\Desktop\NVIDEA\smart-attendance\backend"

proc = subprocess.Popen(
    [sys.executable, "-m", "uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000"],
    cwd=backend_dir,
    stdout=subprocess.PIPE,
    stderr=subprocess.PIPE,
)
time.sleep(3)

try:
    resp = urllib.request.urlopen("http://127.0.0.1:8000/api/health")
    print("Health check:", json.loads(resp.read()))
except Exception as e:
    print(f"Error: {e}")

proc.terminate()
proc.wait()
print("Done")