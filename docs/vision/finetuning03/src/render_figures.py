"""Render measured comparison and the fixed fusion rule; no scene images used."""
from pathlib import Path
import argparse, json
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.patches import FancyBboxPatch,FancyArrowPatch

ROOT=Path(__file__).resolve().parent.parent
def read(p):return json.loads((ROOT/p).read_text(encoding='utf-8'))

def render(font):
    font_manager.fontManager.addfont(str(font))
    plt.rcParams.update({'font.family':font_manager.FontProperties(fname=str(font)).get_name(),'axes.unicode_minus':False,'font.size':12})
    navy='#002c5f';teal='#008c95';gray='#788391';paper='#f7f9fb'
    ab=read('metrics/pilot03_ab.json');more=read('metrics/additional_models.json');fusion=read('metrics/fusion_development.json')
    names=['기준 n320','A · 학습률','B · BN 고정','C · L2-SP','n640','s640','F1','F2']
    fig,axes=plt.subplots(1,2,figsize=(13,6.7),facecolor='white')
    for ax,scope,title in zip(axes,['pilot01_development_regression','data02_development_val'],['사용자 주행 영상 회귀','영상·이미지 혼합 개발']):
        metrics=[ab['scopes'][scope]['overall'][n]['macroIoUSupportedGT']*100 for n in ('baseline','A','B')]
        metrics += [more['scopes'][scope]['overall'][n]['macroIoUSupportedGT']*100 for n in ('C','n640','s')]
        metrics += [fusion['scopes'][scope]['overall'][n]['macroIoUSupportedGT']*100 for n in ('F1','F2')]
        ax.set_facecolor(paper);ax.barh(names,metrics,color=[gray]+[navy]*6+[teal],height=.55)
        ax.invert_yaxis();ax.set_xlim(0,100);ax.set_title(title,loc='left',fontweight='bold',fontsize=16,pad=15,color=navy)
        ax.set_xlabel('지원 클래스 평균 IoU (%)',labelpad=12);ax.set_axisbelow(True);ax.xaxis.grid(True,alpha=.15)
        for i,v in enumerate(metrics):ax.text(v+1,i,f'{v:.2f}',va='center',fontsize=11,color=navy)
        for side in ('top','right','left'):ax.spines[side].set_visible(False)
        ax.spines['bottom'].set_color('#d4dce3');ax.tick_params(length=0,pad=8)
    fig.suptitle('평균 점수와 핵심 클래스 퇴행을 함께 확인',x=.035,ha='left',y=.98,fontsize=21,fontweight='bold',color=navy)
    fig.text(.035,.025,'F2만 사전 개발 기준 통과  ·  F1은 평균 개선에도 간판 오탐 기준 미통과\n개발 범위별 결과이며 독립 시험 수치가 아님',fontsize=11,color='#526276',linespacing=1.6)
    fig.subplots_adjust(top=.85,bottom=.25,left=.13,right=.98,wspace=.38)
    dest=ROOT/'assets';dest.mkdir(exist_ok=True);fig.savefig(dest/'experiment-comparison.png',dpi=160);plt.close(fig)

    fig,ax=plt.subplots(figsize=(13,7.6),facecolor='white');ax.set_xlim(0,13);ax.set_ylim(0,7.6);ax.axis('off')
    ax.text(.4,7.05,'F2 · 역할을 나누고, 같은 화면에서 결합',fontsize=23,fontweight='bold',color=navy)
    ax.text(.4,6.6,'원본 좌표와 클래스 체계를 맞춘 뒤, 모든 픽셀에 동일한 우선순위 적용',fontsize=12,color='#526276')
    def box(x,y,w,h,title,body,color=navy):
        ax.add_patch(FancyBboxPatch((x,y),w,h,boxstyle='round,pad=0.02,rounding_size=.14',facecolor=paper,edgecolor='#d4dce3'))
        ax.text(x+.22,y+h-.4,title,fontsize=14,fontweight='bold',color=color)
        ax.text(x+.22,y+h-.85,body,fontsize=11,color='#526276',va='top',linespacing=1.7)
    box(.4,3.6,2.3,2.2,'같은 원본 프레임','동일 RGB\n동일 방향·좌표\n모델별 입력 크기만 조정')
    box(3.5,4.6,3.4,1.35,'n320 · 기존 영역 보호','사람·차량·도로·보도·교통시설')
    box(3.5,2.95,3.4,1.35,'n640 · 하늘','보호 영역 밖의 하늘 후보')
    box(3.5,1.3,3.4,1.35,'C · 구조 보완','건물·창문·간판 후보')
    box(7.75,2.2,4.75,3.4,'고정한 결합 순서','기존 보호 영역 유지\n→ 남은 영역의 하늘 반영\n→ 건물·창문·간판 반영\n→ 나머지는 기존 결과 유지',teal)
    for y in (5.25,3.6,1.95):
        ax.add_patch(FancyArrowPatch((2.75,4.6),(3.4,y),arrowstyle='-|>',mutation_scale=12,color='#8798ab',connectionstyle='arc3,rad=0'))
        ax.add_patch(FancyArrowPatch((6.95,y),(7.65,3.9),arrowstyle='-|>',mutation_scale=12,color='#8798ab'))
    ax.text(7.9,1.65,'관측에 쓰는 하늘 마스크 = 결합 결과의 하늘',fontsize=11,color=teal)
    ax.text(.4,.5,'정답을 보고 픽셀별 모델을 고르지 않음  ·  기존 오분류도 보호될 수 있음  ·  창문 인식은 개선되지 않음',fontsize=11,color='#526276')
    fig.subplots_adjust(0,0,1,1);fig.savefig(dest/'specialist-flow.png',dpi=160);plt.close(fig)
    # Separate auxiliary panel: never combine web and driving metrics into one total.
    aux=read('metrics/F2_auxiliary.json');labels=['평균 IoU','하늘','창문','간판','차량 car'];b=[];a=[]
    for n,arr in [('baseline',b),('F2',a)]:
        m=aux['overall'][n];arr.append(m['macroIoUSupportedGT']*100)
        arr.extend(m['classes'][str(k)]['iou']*100 for k in (2,8,43,20))
    fig,ax=plt.subplots(figsize=(10,4.8),facecolor='white');x=list(range(len(labels)))
    ax.bar([i-.18 for i in x],b,.34,label='기준 n320',color=gray);ax.bar([i+.18 for i in x],a,.34,label='고정 F2',color=teal)
    ax.set_xticks(x,labels);ax.set_ylim(0,110);ax.set_ylabel('IoU (%)');ax.set_title('선택 후 한 번 확인한 별도 웹 보조 평가',loc='left',fontweight='bold',color=navy,pad=45)
    ax.legend(frameon=False,ncol=2,loc='lower center',bbox_to_anchor=(.5,1.015));ax.spines[['top','right']].set_visible(False)
    fig.text(.12,.02,'AI 보조 부분 주석 · 사람 정답 없음 · 창문 0→0은 개선을 뜻하지 않음',fontsize=10,color='#526276')
    fig.subplots_adjust(bottom=.18,top=.75);fig.savefig(dest/'auxiliary-comparison.png',dpi=160);plt.close(fig)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--font',type=Path,required=True);render(p.parse_args().font)
