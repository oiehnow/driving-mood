import importlib.util, unittest
from pathlib import Path
import numpy as np
import compare as c
from compare_additional import prep,align

class PublicEvidenceTests(unittest.TestCase):
    def test_class_zero_is_wall_and_255_is_ignored(self):
        gt=np.array([[0,43,255]],np.uint8);pred=np.array([[0,0,43]],np.uint8)
        cm=c.e.confusion(gt,pred)
        self.assertEqual(int(cm.sum()),2)
        self.assertEqual(int(cm[0,0]),1)
        self.assertEqual(int(cm[43,0]),1)
        self.assertIsNone(c.extended_metrics(cm)['classes']['12']['iou'])

    def test_absent_gt_retains_false_positives(self):
        cm=c.e.confusion(np.array([[0,0]],np.uint8),np.array([[43,43]],np.uint8))
        metrics=c.extended_metrics(cm)
        self.assertIsNone(metrics['classes']['43']['iou'])
        self.assertEqual(metrics['classes']['43']['falsePositivePixels'],2)
        self.assertEqual(metrics['signFalsePositiveOnNonSignLabeledRate'],1.)

    def test_padding_does_not_become_source_sky(self):
        rgb=np.zeros((270,480,3),np.uint8);_,box=prep(rgb,640)
        top,left,rh,rw,h,w=box;raw=np.full((1,640,640),2,np.uint8)
        raw[0,top:top+rh,left:left+rw]=6
        self.assertTrue(np.all(align(raw,box,640)==6))

    def test_protected_and_sky_precedence(self):
        path=Path(__file__).parent/'fusion/fusion.py';spec=importlib.util.spec_from_file_location('fusion_test',path)
        f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
        base=np.array([[20,0,0,2]],np.uint8);sky=np.array([[2,2,1,0]],np.uint8);structure=np.array([[43,43,8,0]],np.uint8)
        result,sky_mask,_=f.merge_maps(base,sky,structure)
        self.assertTrue(np.array_equal(result,np.array([[20,2,8,2]],np.uint8)))
        self.assertTrue(np.array_equal(sky_mask,result==2))

    def test_compressed_confusion_matches_published_metrics(self):
        root=Path(__file__).parent.parent
        for name in ('pilot03_ab','additional_models','fusion_development','F2_auxiliary'):
            doc=c.read(root/'metrics'/(name+'.json'));ref=doc['confusionArtifact'];path=root/'metrics'/ref['path']
            self.assertEqual(c.sha(path),ref['sha256'])
            with np.load(path,allow_pickle=False) as arrays:
                for key in ref['keys']:
                    if '__' in key:
                        scope,model=key.split('__');expected=doc['scopes'][scope]['overall'][model]
                    else:expected=doc['overall'][key]
                    actual=c.extended_metrics(arrays[key])
                    self.assertEqual(actual,expected)

if __name__=='__main__':unittest.main()
