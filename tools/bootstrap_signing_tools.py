import hashlib
import json
import pathlib
import shutil
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

root = pathlib.Path(__file__).resolve().parent / "signing-tools"
root.mkdir(exist_ok=True)

def download(url, target, expected, algorithm):
    if not target.exists() or hashlib.file_digest(target.open("rb"), algorithm).hexdigest() != expected:
        with urllib.request.urlopen(url, timeout=60) as response, target.open("wb") as output:
            shutil.copyfileobj(response, output)
    assert hashlib.file_digest(target.open("rb"), algorithm).hexdigest() == expected

def extract(archive, directory):
    directory.mkdir(exist_ok=True)
    with zipfile.ZipFile(archive) as bundle:
        for member in bundle.infolist():
            resolved = (directory / member.filename).resolve()
            assert resolved.is_relative_to(directory.resolve()), member.filename
        bundle.extractall(directory)

with urllib.request.urlopen("https://api.github.com/repos/adoptium/temurin21-binaries/releases/latest", timeout=30) as response:
    java_release = json.load(response)
java_asset = next(item for item in java_release["assets"] if item["name"].startswith("OpenJDK21U-jdk_x64_windows_hotspot_") and item["name"].endswith(".zip"))
checksum_asset = next(item for item in java_release["assets"] if item["name"] == java_asset["name"] + ".sha256.txt")
with urllib.request.urlopen(checksum_asset["browser_download_url"], timeout=30) as response:
    java_checksum = response.read().decode().split()[0]
java = {"link": java_asset["browser_download_url"], "checksum": java_checksum}
assert java["link"].startswith("https://github.com/adoptium/temurin21-binaries/releases/download/")
java_zip = root / "java-21.zip"
download(java["link"], java_zip, java["checksum"], "sha256")
extract(java_zip, root / "java")
java_exe = next((root / "java").glob("*/bin/java.exe"))

with urllib.request.urlopen("https://dl.google.com/android/repository/repository2-3.xml", timeout=30) as response:
    repository = ET.fromstring(response.read())
packages = []
for package in repository.findall("remotePackage"):
    if not package.attrib["path"].startswith("build-tools;"):
        continue
    revision = package.find("revision")
    if revision.find("preview") is not None:
        continue
    version = tuple(int(revision.findtext(part, "0")) for part in ["major", "minor", "micro"])
    for archive in package.findall("archives/archive"):
        if archive.findtext("host-os") == "windows":
            complete = archive.find("complete")
            packages.append((version, complete.findtext("url"), complete.find("checksum").text))
version, archive_url, checksum = max(packages)
sdk_zip = root / "android-build-tools.zip"
download("https://dl.google.com/android/repository/" + archive_url, sdk_zip, checksum, "sha1")
extract(sdk_zip, root / "android")
apksigner = next((root / "android").glob("*/lib/apksigner.jar"))
tools = {"java": str(java_exe.resolve()), "keytool": str(java_exe.with_name("keytool.exe").resolve()),
         "apksigner": str(apksigner.resolve()), "zipalign": str(apksigner.parent.parent / "zipalign.exe"),
         "aapt2": str(apksigner.parent.parent / "aapt2.exe"), "build_tools_version": list(version),
         "java_download_sha256": java["checksum"]}
(root / "tools.json").write_text(json.dumps(tools, indent=2))
print(json.dumps(tools, indent=2))
