"""Download explicitly licensed public stills for offline evaluation, never app runtime.
Provenance/licensing is saved beside each file. No private camera frames are read/uploaded.
"""
import hashlib
import json
from pathlib import Path
import urllib.parse
import urllib.request
import time

ROOT = Path(__file__).resolve().parent.parent / "evaluation"
SOURCES = [
    ("retail-wet", "Mathew Street in the rain at night - between the Cavern Walks and Rubber Soul, Liverpool (2011-08-12 21.21.56 by SomeDriftwood).jpg", "SomeDriftwood", "CC0-1.0", "https://creativecommons.org/publicdomain/zero/1.0/"),
    ("highway-night", "NSHighway to JB night.jpg", "Brandon Lim Lik Wei", "CC-BY-2.0", "https://creativecommons.org/licenses/by/2.0/"),
    ("rain-street", "Raining street in the night - panoramio.jpg", "Harri Hedman", "CC-BY-3.0", "https://creativecommons.org/licenses/by/3.0/"),
    ("dark-lamp", "Persistent Night Rain In The Light Of Street Lamp (220669549).jpeg", "Pavel Soro London", "CC0-1.0", "https://creativecommons.org/publicdomain/zero/1.0/"),
    ("highway-dense", "Eastern Dispersal Link Highway (JOHOR).jpg", "Encik Tekateki", "CC-BY-SA-4.0", "https://creativecommons.org/licenses/by-sa/4.0/"),
]

def get(url):
    time.sleep(2)  # Public server courtesy; a 429 stops this run, no rapid retries.
    req = urllib.request.Request(url, headers={"User-Agent": "UrbanMoodPrototypeEvaluation/0.1 (offline computer vision sample evaluation)"})
    with urllib.request.urlopen(req, timeout=40) as response:
        return response.read()

if __name__ == "__main__":
    ROOT.mkdir(parents=True, exist_ok=True)
    manifest = []
    for key, title, author, license_id, license_url in SOURCES:
        url = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode({
            "action": "query", "format": "json", "prop": "imageinfo", "iiprop": "url|extmetadata", "titles": "File:" + title,
        })
        cached=ROOT / (key + "-source.json")
        if cached.exists():
            info=json.loads(cached.read_text(encoding="utf-8"))
        else:
            data = json.loads(get(url))
            page = next(iter(data["query"]["pages"].values()))
            info = page["imageinfo"][0]
            cached.write_text(json.dumps(info, ensure_ascii=False, indent=2), encoding="utf-8")
        image_url = info["url"]
        assert urllib.parse.urlparse(image_url).hostname == "upload.wikimedia.org"
        local=ROOT / (key + ".jpg")
        payload = local.read_bytes() if local.exists() else get(image_url)
        local.write_bytes(payload)
        manifest.append({"id": key, "title": title, "author": author, "license": license_id, "licenseUrl": license_url,
                         "source": info["descriptionurl"], "imageUrl": image_url, "sha256": hashlib.sha256(payload).hexdigest(),
                         "changes": "Original bytes unchanged. Evaluation makes resized RGB copies and boxes; those are marked separately."})
        print(key, len(payload))
        (ROOT / "sources.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
