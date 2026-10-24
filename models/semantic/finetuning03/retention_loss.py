"""Independent L2-SP formula, original official supervised loss unchanged."""
import torch
from torch import nn
class RetentionLoss(nn.Module):
    def __init__(self,supervised,model,coefficient):
        super().__init__()
        self.supervised=supervised
        params=[p for p in model.parameters() if p.requires_grad]
        # Plain lists avoid registering the model recursively or altering state_dict/export.
        object.__setattr__(self,'trainable_refs',params)
        object.__setattr__(self,'anchors',[p.detach().float().clone() for p in params])
        self.elements=sum(p.numel() for p in params)
        self.coefficient=float(coefficient)
        self.last_record={}
    def regularizer(self):
        return sum((p.float()-anchor).square().sum() for p,anchor in zip(self.trainable_refs,self.anchors))/self.elements
    def forward(self,preds,batch):
        supervised,items=self.supervised(preds,batch)
        regularizer=self.regularizer()
        addition=regularizer*self.coefficient*batch['img'].shape[0]
        self.last_record=dict(meanSquaredDeviation=float(regularizer.detach()),
            coefficient=self.coefficient,addedBatchScaledLoss=float(addition.detach()),
            supervisedBatchScaledLoss=float(supervised.detach()))
        return supervised+addition,items

def verify_formula():
    model=nn.Linear(2,1,bias=False)
    model.weight.data.copy_(torch.tensor([[1.,2.]]))
    loss=RetentionLoss(nn.Identity(),model,3.)
    assert loss.regularizer().item()==0
    anchor=loss.anchors[0].clone()
    with torch.no_grad():model.weight[0,0]+=0.5
    penalty=loss.regularizer()
    assert abs(penalty.item()-.125)<1e-7
    penalty.backward()
    assert torch.allclose(model.weight.grad,torch.tensor([[.5,0.]]))
    assert torch.equal(anchor,loss.anchors[0])
    assert list(loss.state_dict())==[]
    return dict(initialPenaltyZero=True,analyticValueAndGradientMatch=True,anchorUnchanged=True,stateDictUnchanged=True)
if __name__=='__main__':print(verify_formula())
