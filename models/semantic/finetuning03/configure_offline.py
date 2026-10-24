"""Keep this pilot local; no cloud training integrations or data sync."""
import json,os
from pathlib import Path
ROOT=Path(__file__).resolve().parent
os.environ['YOLO_CONFIG_DIR']=str(ROOT/'ultralytics-config')
os.environ['YOLO_OFFLINE']='true'
os.environ['WANDB_DISABLED']='true'
os.environ['COMET_MODE']='DISABLED'
path=ROOT/'ultralytics-config/Ultralytics/settings.json'
if path.exists():
    settings=json.loads(path.read_text())
    for key in ['sync','clearml','comet','dvc','mlflow','raytune','tensorboard','wandb']:settings[key]=False
    settings['datasets_dir']=str(ROOT/'staged-data');settings['weights_dir']=str(ROOT/'weights');settings['runs_dir']=str(ROOT/'runs')
    path.write_text(json.dumps(settings,indent=2),encoding='utf-8')
