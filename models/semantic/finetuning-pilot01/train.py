import argparse,hashlib,json,os
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--weights',required=True);p.add_argument('--data',required=True);p.add_argument('--output',required=True);a=p.parse_args()
root=Path(__file__).resolve().parent
os.environ['YOLO_CONFIG_DIR']=str(Path(a.output).resolve()/'local-config');os.environ['YOLO_OFFLINE']='true';os.environ['WANDB_DISABLED']='true';os.environ['COMET_MODE']='DISABLED'
import torch
from ultralytics import YOLO,settings
torch.set_num_threads(4)
assert hashlib.sha256(Path(a.weights).read_bytes()).hexdigest()=='68ac71bef2868c987fff7cd7c49cb656922f6d8447ad21af3893305577435000'
settings.update({k:False for k in ['sync','clearml','comet','dvc','mlflow','raytune','tensorboard','wandb']})
config=json.loads((root/'pilot_config.json').read_text())
YOLO(a.weights,task='semantic').train(data=a.data,project=a.output,name='pilot-01',**config)
