"""Stage an authorized copy of the exact existing RGB/mask inputs. Generates no labels."""
import argparse,hashlib,json,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parent
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,v):p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
def main():
    parser=argparse.ArgumentParser();parser.add_argument('authorized_staged_data',type=Path)
    args=parser.parse_args();source=args.authorized_staged_data.resolve()
    manifest=json.loads((ROOT/'input-manifest.json').read_text())
    files={item['path']:item['sha256'] for sample in manifest['samples'] for item in sample['files'].values()}
    for relative,expected in files.items():
        p=(source/relative).resolve()
        assert p.is_relative_to(source) and sha(p)==expected,f'Input missing or changed: {relative}'
    destination=ROOT/'staged-data';assert not destination.exists(),'Preserve prior staging; use a clean package copy'
    destination.mkdir()
    for relative in files:
        p=destination/relative;p.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source/relative,p)
    dataset=json.loads((ROOT/'dataset-template.json').read_text());dataset['path']=destination.as_posix()
    save(destination/'dataset.yaml',dataset)
    probe=dict(dataset,val=dataset['train']);save(destination/'probe-train-only.yaml',probe)
    save(destination/'manifest.json',manifest)
    save(ROOT/'data-freeze.json',dict(status='ready',ignoreIndex=255,numClasses=150,
        stagedFileHashes={p.relative_to(ROOT).as_posix():sha(p) for p in destination.rglob('*') if p.is_file()},
        trainingConfigSha256=sha(ROOT/'pilot_config.json'),selection='Development split only',newLabelsGenerated=False))
    print('Exact staged input hashes verified; ready for the train-only probe.')
if __name__=='__main__':main()
