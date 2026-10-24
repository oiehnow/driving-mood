"""Render original documentation diagrams (not app screenshots or model outputs).

Requires Pillow. Run from any directory: python scripts/render-project-visuals.py
No network access, photos, API keys, or app execution are used.
"""
from pathlib import Path
from functools import lru_cache
from PIL import Image, ImageDraw, ImageFont

OUT = Path(__file__).resolve().parents[1] / "docs/product/assets"
BLUE = "#002C5F"
INK = "#15304D"
MUTED = "#52677D"
BG = "#F2F6FA"
LINE = "#C4D2E0"
TEAL = "#007B83"


@lru_cache(maxsize=64)
def font(size, bold=False):
    path = Path(__file__).resolve().parents[1] / "app/src/main/res/font/pretendard_variable.ttf"
    face = ImageFont.truetype(str(path), size)
    face.set_variation_by_axes([700 if bold else 400])
    return face


class Canvas:
    def __init__(self, width, height, title, subtitle, badge="DRIVING MOOD"):
        self.im = Image.new("RGB", (width, height), BG)
        self.d = ImageDraw.Draw(self.im)
        self.d.rectangle((0, 0, width, 11), fill=BLUE)
        self.text(48, 32, badge, 18, True, TEAL)
        self.text(48, 68, title, 42, True, BLUE)
        self.text(48, 130, subtitle, 22, color=MUTED)

    def text(self, x, y, text, size=24, bold=False, color=INK):
        self.d.text((x, y), text, font=font(size, bold), fill=color)

    def card(self, rect, title, lines, dark=False, tag=None, accent=False, size=23):
        x, y, r, b = rect
        self.d.rounded_rectangle(rect, 18, fill=BLUE if dark else "white",
                                 outline=TEAL if accent else LINE, width=3 if accent else 1)
        color = "white" if dark else INK
        offset = 22
        if tag:
            self.text(x+24, y+18, tag, 17, True, "#A4DEE0" if dark else TEAL)
            offset = 49
        self.text(x+24, y+offset, title, 28, True, color)
        for i, line in enumerate(lines):
            self.text(x+24, y+offset+48+i*35, line, size, color=color if dark else MUTED)

    def arrow(self, points, color=TEAL):
        self.d.line(points, fill=color, width=4, joint="curve")
        x, y = points[-1]
        px, py = points[-2]
        if abs(x-px) > abs(y-py):
            sign = 1 if x > px else -1
            self.d.polygon([(x,y),(x-12*sign,y-7),(x-12*sign,y+7)], fill=color)
        else:
            sign = 1 if y > py else -1
            self.d.polygon([(x,y),(x-7,y-12*sign),(x+7,y-12*sign)], fill=color)

    def save(self, name):
        self.im.save(OUT / name, optimize=True)


def overview():
    c = Canvas(1440, 800, "풍경과 맥락에서, 무드 키워드로", "환경을 해석한 키워드를 음악과 다양한 차량 경험에 연결합니다.")
    c.card((48,210,410,360), "주변 환경", ["카메라 · 시간 · 기상청 날씨"], tag="현재 입력")
    c.card((48,380,410,555), "사용자 선택", ["원하는 분위기", "선호 장르 · 곡별 태그"], tag="현재 입력", size=22)
    c.card((48,570,410,720), "주행 · 감성 맥락", ["내비게이션 · 기분 피드백"], tag="후속 입력")
    c.card((510,310,935,590), "무드 키워드", ["유효한 정보만 결합", "키워드와 근거 · 누락 상태", "예: 야간 / 차분한 / 따뜻한"], dark=True, tag="제품의 중심")
    for y in (285,465,645):
        c.arrow([(420,y),(455,y),(455,450),(500,450)])
    c.card((1040,210,1392,360), "음악", ["로컬 음원 추천 · 재생"], tag="현재 활용", accent=True)
    c.card((1040,390,1392,540), "실내조명", ["선호색 · 밝기 프리셋"], tag="후속 확장")
    c.card((1040,570,1392,720), "주변 장소", ["분위기에 맞는 장소 추천"], tag="후속 확장")
    for y in (285,465,645):
        c.arrow([(945,450),(985,450),(985,y),(1030,y)])
    c.text(48,755,"사용자 목표는 음악 추천에 우선 반영 · 공용 점수기로 근거 연결 · 차량 연동은 후속 개발",20,color=MUTED)
    c.save("mood-overview.png")


def algorithm():
    c = Canvas(1440, 1060, "무드 키워드는 어떻게 계산할까", "50초 관측 · 객체별 기여 점수 · 시간 구간 누적")
    c.card((48,205,430,405), "위치 · 날씨 확인", ["위치 → 기상청 격자", "유효 캐시 재사용 또는 조회", "실패 · 누락 상태 함께 전달"], tag="01  요청 시 한 번", size=22)
    c.card((530,205,912,405), "카메라 관측", ["요청마다 최대 50초", "방향 · 크기 보정", "권한 거부 시 영상 제외"], tag="02  시간 구간 수집", size=22)
    c.card((1010,205,1392,405), "사람 · 차량 관측", ["객체 검출과 영역 연결", "차량광 제외", "검출 근거 기록"], tag="03  모델", size=22)
    c.arrow([(440,305),(520,305)])
    c.arrow([(922,305),(1000,305)])
    c.card((1010,470,1392,670), "색감 · 광량 측정", ["HSV · 밝기 · 국소 대비", "빛나는 연결 영역 측정", "도로 쪽 영역 · 광점 억제"], tag="04  픽셀 규칙", size=22)
    c.arrow([(1200,415),(1200,460)])
    c.card((530,470,912,705), "관측별 사용 조건", ["사람 · 조명 · 색감 각각 확인", "미지원 · 누락은 별도 표시", "1초 구간마다 대표 관측", "고속도로 조명 해석 제한"], dark=True, tag="05  품질 · 시간", size=21)
    c.arrow([(1000,575),(922,575)])
    c.card((48,470,430,705), "무드별 기여 계산", ["객체별 가산 · 감점과 이유", "활력 · 따뜻함 · 차가움 등", "기여 원장과 박스 연결", "사용자 목표는 선곡에 우선"], tag="06  점수 · 근거", size=21)
    c.arrow([(520,575),(440,575)])
    c.arrow([(239,415),(239,460)], color=LINE)
    c.text(556,724,"프레임별 관측 → 구간 평균 → 최종 키워드",20,color=MUTED)
    c.card((48,800,890,1005), "음악 후보와 연결", ["원하는 분위기 우선 · 장르와 태그에 가중치 부여", "최근 재생 곡은 뒤로 보내고 가중 무작위로 순서 결정", "완료된 분석 시각의 키워드를 사용"], tag="07  현재 활용", size=24)
    c.card((980,800,1392,1005), "결과 유지", ["키워드 · 근거 · 분석 시각", "카메라 · 위치 수집 종료", "다시 누르면 새 분석"], tag="08  사용자 화면", size=23)
    c.arrow([(239,715),(239,790)])
    c.arrow([(900,900),(970,900)])
    c.save("algorithm-pipeline.png")


def request_animation():
    titles = ["분석 요청", "위치 · 날씨", "카메라 분석", "무드 계산", "결과 유지"]
    details = [
        ("분석 버튼으로 시작", ["카메라와 위치 권한 확인", "새 분석 요청 생성"]),
        ("주행 환경 확인", ["현재 위치로 기상청 조회 지역 결정", "유효한 날씨 캐시 재사용"]),
        ("50초 동안 장면 관측", ["사람 · 차량 · 주변 조명과 색감", "1초 구간별 대표 관측 저장"]),
        ("관측별 기여로 무드 점수를 계산합니다", ["차량 불빛은 조명에서 제외하고 가산·감점의 이유를 남깁니다.","시간 구간별 대표 관측을 모아 최종 점수와 키워드를 만듭니다."]),
        ("무드 결과와 음악 추천", ["환경 키워드와 주요 기여 표시", "원하는 분위기와 선호 장르 반영"]),
    ]
    frames=[]
    for index, (heading, lines) in enumerate(details):
        c=Canvas(1280,700,"프로토타입의 분석 매커니즘", "환경 관측 → 무드 키워드 → 음악 추천")
        for i,title in enumerate(titles):
            x=48+i*244
            active=i==index
            c.card((x,220,x+208,355),title,[],dark=active,tag=f"0{i+1}")
            if i<4:c.arrow([(x+213,288),(x+237,288)],TEAL if i<index else LINE)
        c.card((48,405,1232,600),heading,lines,tag=f"STEP {index+1} / 5",size=24)
        c.d.rounded_rectangle((48,643,1232,650),4,fill=LINE)
        c.d.rounded_rectangle((48,643,48+int(1184*(index+1)/5),650),4,fill=TEAL)
        frames.append(c.im)
    frames[0].save(OUT/"request-flow.gif",save_all=True,append_images=frames[1:],duration=[2000,2400,2400,2400,3000],loop=0,optimize=True)
    frames[-1].save(OUT/"request-flow-static.png",optimize=True)


def keywords():
    c=Canvas(1440,480,"더 다양한 무드 키워드", "환경에서 얻은 단서와 사용자가 원하는 분위기를 구분합니다.")
    c.card((48,205,466,395),"차가운",["품질을 통과한 파랑 · 청록 색감", "또는 사용자가 직접 선택"],tag="색감 단서 / 선택",size=22)
    c.card((510,205,928,395),"우울",["사용자가 원하는 음악 분위기", "차분한 장르와 곡 태그 연결"],tag="사용자 선택",size=22)
    c.card((972,205,1390,395),"활기 넘침",["사람 · 주변 조명의 기여", "또는 사용자가 직접 선택"],tag="장면 단서 / 선택",size=22)
    c.text(48,430,"환경에서 얻은 분위기와 사용자의 목표를 함께 반영합니다.",21,color=MUTED)
    c.save("mood-keywords.png")


if __name__ == "__main__":
    OUT.mkdir(parents=True,exist_ok=True)
    overview()
    algorithm()
    request_animation()
    keywords()
    for name in ("mood-overview.png","algorithm-pipeline.png","request-flow.gif","request-flow-static.png","mood-keywords.png"):
        print(f"{name}: {(OUT/name).stat().st_size:,} bytes")
