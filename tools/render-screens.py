"""README 용 앱 화면 목업을 만든다.

`CarRadar.kt` / `Color.kt` 의 **실제 상수를 파싱해서** 그리므로, UI 수치를 바꾸면
이 스크립트를 다시 돌리는 것만으로 그림이 따라온다. 손으로 그린 시안이 아니다.

Compose Canvas 와 SVG 는 좌표계(y 아래로 증가)와 각도 기준(0°=3시, +가 시계방향)이
같아서 부채꼴 기하가 1:1로 일치한다. 다만 **글꼴과 안티앨리어싱은 실제 안드로이드
렌더링과 다르므로, 이건 스크린샷이 아니라 목업이다.**

사용법:

    python3 tools/render-screens.py docs/images        # HTML 생성
    # 이어서 헤드리스 크로미움으로 PNG 저장
    for s in main-parking main-blindzone main-driving; do
      chrome --headless --screenshot=docs/images/$s.png --window-size=360,780 \
             file://$PWD/docs/images/$s.html
    done
    chrome --headless --screenshot=docs/images/settings.png --window-size=360,1140 \
           file://$PWD/docs/images/settings.html
"""
import math
import re
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parent.parent
SRC = REPO / "android/ParkAssist/app/src/main/java/com/parkassist/ui"
OUT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
OUT.mkdir(parents=True, exist_ok=True)


def consts(path):
    text = (SRC / path).read_text()
    return {m.group(1): float(m.group(2))
            for m in re.finditer(r"private const val (\w+) = ([\d.]+)f?", text)}


def colors():
    text = (SRC / "theme/Color.kt").read_text()
    out = {}
    for m in re.finditer(r"val (\w+) = Color\(0x(\w+)\)", text):
        v = int(m.group(2), 16) & 0xFFFFFFFF          # Compose 도 하위 32비트만 쓴다
        a, r, g, b = (v >> 24) & 0xFF, (v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF
        out[m.group(1)] = f"rgba({r},{g},{b},{a/255:.3f})"
    return out


C = consts("main/CarRadar.kt")
COL = colors()

LEVEL_COLOR = {"NONE": COL["LevelNone"], "CAUTION": COL["LevelCaution"], "WARN": COL["LevelWarn"],
               "DANGER": COL["LevelDanger"], "CRITICAL": COL["LevelCritical"]}
LIT = {"NONE": 0, "CAUTION": 1, "WARN": 2, "DANGER": 3, "CRITICAL": 3}
N, CA, W_, D, CR = "NONE", "CAUTION", "WARN", "DANGER", "CRITICAL"


def alpha(rgba, a):
    return re.sub(r",[\d.]+\)$", f",{a})", rgba)


def arc(ox, oy, r, a0, sweep, color, width):
    p0 = (ox + r * math.cos(math.radians(a0)), oy + r * math.sin(math.radians(a0)))
    p1 = (ox + r * math.cos(math.radians(a0 + sweep)), oy + r * math.sin(math.radians(a0 + sweep)))
    return (f'<path d="M {p0[0]:.2f} {p0[1]:.2f} A {r:.2f} {r:.2f} 0 {1 if abs(sweep)>180 else 0} 1 '
            f'{p1[0]:.2f} {p1[1]:.2f}" fill="none" stroke="{color}" '
            f'stroke-width="{width:.2f}" stroke-linecap="round"/>')


def radar(side, rear, front):
    """rear/front = 채널 4개 레벨 (index 0 = CH1 = 차량 왼쪽)"""
    radius = side / 2
    cx = cy = side / 2
    s = [f'<svg width="{side}" height="{side}" viewBox="0 0 {side} {side}">']

    s.append(f'<circle cx="{cx}" cy="{cy}" r="{radius-radius*0.012:.2f}" fill="none" '
             f'stroke="{alpha(COL["RingAccent"],0.45)}" stroke-width="{radius*0.024:.2f}"/>')
    s.append(f'<circle cx="{cx}" cy="{cy}" r="{radius*0.93:.2f}" fill="none" '
             f'stroke="{alpha(COL["RingAccent"],0.10)}" stroke-width="{radius*0.008:.2f}"/>')

    car_w, car_h = radius * C["CAR_WIDTH_RATIO"], radius * C["CAR_HEIGHT_RATIO"]
    car_top = cy - car_h / 2
    rear_o, front_o = (cx, cy + car_h / 2), (cx, car_top)

    drop, spread, stroke = radius * 0.34, car_w * 0.34, radius * 0.010
    for side_sign in (-1, 1):
        s.append(f'<line x1="{rear_o[0]+side_sign*car_w/2:.2f}" y1="{rear_o[1]:.2f}" '
                 f'x2="{rear_o[0]+side_sign*(car_w/2+spread):.2f}" y2="{rear_o[1]+drop:.2f}" '
                 f'stroke="{COL["GuideLine"]}" stroke-width="{stroke:.2f}" stroke-linecap="round"/>')

    band_stroke = radius * C["BAND_STROKE_RATIO"]
    sweep = C["TOTAL_SWEEP"] / C["BAND_SECTORS"]
    for zone, origin, levels in (("REAR", rear_o, rear), ("FRONT", front_o, front)):
        for i, level in enumerate(levels):
            start = (C["REAR_START_ANGLE"] + (C["BAND_SECTORS"]-1-i) * sweep) if zone == "REAR" \
                else (C["FRONT_START_ANGLE"] + i * sweep)
            for band in range(int(C["BAND_COUNT"])):
                on = band >= C["BAND_COUNT"] - LIT[level]
                r = radius * (C["FIRST_BAND_RATIO"] + band * C["BAND_SPACING_RATIO"])
                s.append(arc(origin[0], origin[1], r, start + C["SECTOR_GAP"]/2,
                             sweep - C["SECTOR_GAP"],
                             LEVEL_COLOR[level] if on else COL["BandIdle"], band_stroke))

    s.append(f'<rect x="{cx-car_w/2:.2f}" y="{car_top:.2f}" width="{car_w:.2f}" height="{car_h:.2f}" '
             f'rx="{car_w*0.32:.2f}" fill="{COL["CarBody"]}"/>')
    cab_w = car_w * 0.72
    s.append(f'<rect x="{cx-cab_w/2:.2f}" y="{car_top+car_h*0.20:.2f}" width="{cab_w:.2f}" '
             f'height="{car_h*0.38:.2f}" rx="{car_w*0.18:.2f}" fill="{COL["CarGlass"]}"/>')
    mw, mh, my = car_w*0.13, car_h*0.07, car_top + car_h*0.24
    for x in (cx - car_w/2 - mw*0.7, cx + car_w/2 - mw*0.3):
        s.append(f'<rect x="{x:.2f}" y="{my:.2f}" width="{mw:.2f}" height="{mh:.2f}" '
                 f'rx="{mw/2:.2f}" fill="{COL["CarBody"]}"/>')
    return "".join(s) + "</svg>"


W, H = 360, 780
FRAME = (f'width:{W}px;height:{H}px;background:{COL["Background"]};color:{COL["OnBackground"]};'
         f'font-family:"Noto Sans KR",sans-serif;box-sizing:border-box;overflow:hidden;'
         f'display:flex;flex-direction:column')


def tab(label, on):
    bg = COL["RingAccent"] if on else "transparent"
    fg = COL["Background"] if on else COL["OnSurfaceMuted"]
    return (f'<div style="flex:1;height:64px;display:flex;align-items:center;justify-content:center;'
            f'background:{bg};color:{fg};font-size:28px;font-weight:600">{label}</div>')


def chip(label, color):
    return (f'<div style="display:flex;align-items:center;gap:9px;background:{COL["Surface"]};'
            f'border-radius:99px;padding:10px 14px;font-size:20px">'
            f'<div style="width:14px;height:14px;border-radius:50%;background:{color}"></div>{label}</div>')


def main_screen(mode, rear, front, readout, front_on=True, rear_on=True):
    used = 12 + 64 + 12 + 44 + 132 + 16 + 88 + 12
    box_h = H - used
    side = min(W - 40, box_h)          # 수정된 코드: fillMaxSize → min(w, h)
    return f"""<div style="{FRAME};padding:12px 20px;align-items:center">
  <div style="display:flex;width:100%;border-radius:18px;overflow:hidden;background:{COL["Surface"]};flex:none">
    {tab("주차", mode=="주차")}{tab("주행", mode=="주행")}</div>
  <div style="height:12px;flex:none"></div>
  <div style="display:flex;width:100%;justify-content:space-between;align-items:center;flex:none">
    <div style="display:flex;gap:10px">
      {chip("전방", COL["LevelCaution"] if front_on else COL["LevelDanger"])}
      {chip("후방", COL["LevelCaution"] if rear_on else COL["LevelDanger"])}</div>
    <div style="color:{COL["OnSurfaceMuted"]};font-size:20px">🔋 81%</div></div>
  <div style="flex:1;width:100%;display:flex;align-items:center;justify-content:center">{radar(side, rear, front)}</div>
  <div style="height:132px;flex:none;display:flex;align-items:center;justify-content:center;width:100%">{readout}</div>
  <div style="height:16px;flex:none"></div>
  <div style="display:flex;width:100%;gap:14px;align-items:center;flex:none">
    <div style="flex:1;height:88px;border-radius:20px;background:{COL["LevelDanger"]};display:flex;
                align-items:center;justify-content:center;gap:12px;font-size:32px;font-weight:700">⏻ 전원 끄기</div>
    <div style="width:88px;height:88px;border-radius:20px;background:{COL["Surface"]};display:flex;
                align-items:center;justify-content:center;font-size:44px">⚙</div></div></div>"""


def big(zone, num, color):
    return (f'<div style="text-align:center"><span style="color:{COL["OnSurfaceMuted"]};font-size:24px">{zone}</span><br>'
            f'<span style="font-size:120px;font-weight:700;line-height:1;color:{color}">{num}</span>'
            f'<span style="font-size:40px;font-weight:700;color:{color}"> cm</span></div>')


def phrase(zone, text, color):
    head = f'<span style="color:{COL["OnSurfaceMuted"]};font-size:24px">{zone}</span><br>' if zone else ""
    return f'<div style="text-align:center">{head}<span style="font-size:64px;font-weight:700;color:{color}">{text}</span></div>'


def slider(label, value, color, lo, hi):
    pct = (value - lo) / (hi - lo) * 100
    return f"""<div style="margin-bottom:24px">
  <div style="display:flex;justify-content:space-between;align-items:center">
    <span style="font-size:24px;font-weight:600;color:{color}">{label}</span>
    <span style="font-size:32px;font-weight:700">{value} cm</span></div>
  <div style="height:8px"></div>
  <div style="display:flex;align-items:center;gap:12px">
    <div style="width:60px;height:60px;border-radius:50%;background:{COL["SurfaceVariant"]};
                display:flex;align-items:center;justify-content:center;font-size:32px;flex:none">−</div>
    <div style="flex:1;position:relative;height:20px;display:flex;align-items:center">
      <div style="height:6px;width:100%;border-radius:3px;background:{COL["Outline"]}"></div>
      <div style="height:6px;width:{pct:.0f}%;border-radius:3px;background:{COL["RingAccent"]};position:absolute"></div>
      <div style="width:22px;height:22px;border-radius:50%;background:{COL["RingAccent"]};
                  position:absolute;left:calc({pct:.0f}% - 11px)"></div></div>
    <div style="width:60px;height:60px;border-radius:50%;background:{COL["SurfaceVariant"]};
                display:flex;align-items:center;justify-content:center;font-size:32px;flex:none">+</div></div></div>"""


def toggle(title, desc, on):
    knob = "flex-end" if on else "flex-start"
    bg = COL["RingAccent"] if on else COL["Outline"]
    return f"""<div style="display:flex;align-items:center;background:{COL["Surface"]};
              border-radius:18px;padding:20px;margin-bottom:16px">
  <div style="flex:1">
    <div style="font-size:24px;font-weight:600">{title}</div><div style="height:6px"></div>
    <div style="font-size:20px;color:{COL["OnSurfaceMuted"]};line-height:1.35">{desc}</div></div>
  <div style="width:56px;height:32px;border-radius:16px;background:{bg};display:flex;
              align-items:center;justify-content:{knob};padding:3px;box-sizing:border-box;flex:none">
    <div style="width:26px;height:26px;border-radius:50%;background:#fff"></div></div></div>"""


def settings_screen():
    # 실제 화면은 verticalScroll 이라 스크롤된다. 여기서는 전체 내용이 보이도록 높이를 늘렸다.
    frame = FRAME.replace(f"height:{H}px", "height:1140px")
    return f"""<div style="{frame}">
  <div style="display:flex;align-items:center;gap:12px;padding:12px 16px;flex:none">
    <span style="font-size:36px">←</span><span style="font-size:28px;font-weight:600">설정</span></div>
  <div style="padding:12px 20px;overflow:hidden">
    <div style="font-size:32px;font-weight:700">거리 기준 · 주차</div>
    <div style="height:8px"></div>
    <div style="font-size:20px;color:{COL["OnSurfaceMuted"]};line-height:1.4">
      경고음이 울리기 시작하는 거리입니다. 가까워질수록 소리가 촘촘하고 커집니다.</div>
    <div style="height:20px"></div>
    {slider("위험 (빨강)", 30, COL["LevelDanger"], 10, 80)}
    {slider("경고 (주황)", 60, COL["LevelWarn"], 20, 150)}
    {slider("주의 (초록)", 120, COL["LevelCaution"], 40, 254)}
    <div style="height:80px;border-radius:18px;background:{COL["SurfaceVariant"]};color:{COL["OnSurfaceMuted"]};
                display:flex;align-items:center;justify-content:center;font-size:32px;font-weight:700">저장</div>
    <div style="height:28px"></div><div style="height:1px;background:{COL["Outline"]}"></div><div style="height:28px"></div>
    {toggle("앱 경고음", "기기 부저는 이 설정과 관계없이 항상 울립니다", True)}
    {toggle("목업 모드", "실제 기기 없이 가짜 거리 데이터로 화면을 확인합니다", False)}
  </div></div>"""


SCREENS = {
    "main-parking": main_screen("주차", [W_, D, D, W_], [N, N, CA, N], big("후방", 25, COL["LevelDanger"])),
    "main-blindzone": main_screen("주차", [D, CR, CR, D], [N, N, N, N],
                                  phrase("후방", "매우 가까움", COL["LevelCritical"])),
    "main-driving": main_screen("주행", [N, N, N, N], [W_, D, D, W_],
                                phrase("", "전방 급접근!", COL["LevelDanger"])),
    "settings": settings_screen(),
}

for name, body in SCREENS.items():
    (OUT / f"{name}.html").write_text(
        f'<html><body style="margin:0;background:#000">{body}</body></html>')

(OUT / "all.html").write_text(
    '<html><body style="margin:0;padding:20px;background:#0d1117;display:flex;gap:20px">'
    + "".join(f'<div style="border:1px solid #30363d">{b}</div>' for b in SCREENS.values())
    + "</body></html>")
print("screens:", ", ".join(SCREENS))
