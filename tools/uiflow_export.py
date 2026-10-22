#!/usr/bin/env python3
import sys, pathlib; sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
"""UI 흐름도 PNG 생성기.

artifacts/uiflow/*.mmd 를 읽어 같은 폴더에 PNG를 다시 만든다.
렌더는 tools/mermaid_png.py 가 맡는다.
"""
import pathlib

from mermaid_png import render

UIFLOW = pathlib.Path(__file__).resolve().parent.parent / "artifacts" / "uiflow"

# 파일 이름은 발표 자료와 문서가 그대로 참조하므로 원본과 출력을 여기서 짝지어 고정한다.
# 3번은 순차 단계가 7개라 한 장으로는 세로로 깊어져 슬라이드에서 글자가 8pt 아래로
# 떨어졌다. 액터 경계에서 둘로 나눴다. 경계를 넘는 간선은 S08 → S09 하나뿐이라
# 그 이음매만 양쪽 도면에 겹쳐 그린다.
DIAGRAMS = [
    ("flow-1-auth.mmd", "UI흐름도-1-인증과진입.png"),
    ("flow-2-record.mmd", "UI흐름도-2-평상시기록축적.png"),
    ("flow-3-handover-request.mmd", "UI흐름도-3-교체요청과패키지생성.png"),
    ("flow-4-handover-learn.mmd", "UI흐름도-4-학습과완료승인.png"),
]

if __name__ == "__main__":
    raise SystemExit(render([(UIFLOW / s, UIFLOW / o) for s, o in DIAGRAMS]))
