# 시안 1 — SPD 원본 12×15 위에 레이어 종이인형 넓히기

## 접근
- 업스트림 영웅 스프라이트(6직업, **천 갑옷 tier 1 줄**, 18프레임)를 그대로 몸통으로 쓴다. tier 0은 허리천뿐이라 옷 색이 거의 안 보여서 tier 1을 골랐다.
- 기존 `web-client/scripts/generate-avatars.py` 방식(첫 번째 눈 픽셀 기준 앵커, 팔레트 인덱스 픽셀맵, 5단 램프 재채색)을 복사해서 넓혔다. 기존 파일은 손대지 않았다.
- 새로 넣은 단계: 원래 머리 픽셀을 **민머리 두개골 맵(SKULL)**으로 바꾼 뒤 모든 머리 모양을 그 위에 그린다. 그래서 직업마다 원래 머리 모양이 새 머리 모양에 섞이지 않는다.
- 무늬(trim/sash/twotone)는 손으로 그린 맵이 아니고 **옷 픽셀 마스크에서 자동으로 만든다**. 그래서 달리기·공격 포즈를 그대로 따라간다.
- 등 장식은 "몸 뒤"와 "몸 앞" 두 줄로 나눴다. 3/4 시점에서는 등이 화면 왼쪽에 보이므로, 망토·배낭은 대부분 몸 앞 줄에서 뒤쪽 팔 위에 그린다. 몸 뒤 줄만으로는 12px 폭 안에서 1px도 안 삐져나왔다.

## 파일
| 파일 | 내용 |
|---|---|
| `sheet.png` | 1296×570, 투명 배경. 직업당 216px 열 블록(18프레임), 레이어 키당 15px 행 38줄. 원래 색으로 구워져 있음 |
| `sheet.json` | 행 순서, 그리는 순서, 직업별 원본 램프, 옵션 램프, 모자별 hairClip, 프레임별 눈 앵커와 좌우 반전 여부 |
| `preview.png` | ×6 확인용 시트(축별 단독 / 완성 12명 / 18프레임 애니메이션 / 하수도 타일+쥐 스케일) |
| `avatar_layers.py` | 레이어 생성과 합성 기준 구현 → `sheet.png`, `sheet.json` |
| `build_preview.py` | `preview.png` 생성 (`python3 build_preview.py`) |

## 옵션 수
| 축 | 수 | 항목 |
|---|---|---|
| 피부 | 6 (+직업 기본) | light, pale, tan, brown, deep, orc* |
| 머리 색 | 10 (+기본) | black, chestnut, auburn, ginger, blonde, silver, teal*, violet*, rose*, lime* |
| 머리 모양 | 10 (+original) | bald, crop, swept, long, bob, ponytail, braid, mohawk, curly, spiky |
| 옷 팔레트 | 9 (+기본) | slate, forest, wine, ocean, crimson, violet, ochre, charcoal, snow (주색+보조색 2램프) |
| 옷 무늬 | 3 (+없음) | trim, sash, twotone |
| 머리 장식 | 8 (+없음) | hood, wizard, helm, bandana, crown, horns, cap, headband |
| 얼굴 | 6 (+없음) | glasses, eyepatch, beard, moustache, mask, scar |
| 등/목 | 4 (+없음) | cape, scarf, quiver, backpack |

\* 표시는 자연에 없는 색이다.
- 조합 수(기본값 포함): 직업당 6×11×11×10×4×9×7×5 = **9,147,600**, 6직업이면 **54,885,600**.
- 1×에서 실패한 옵션을 빼면 직업당 2,520,000, 6직업이면 15,120,000이다. 다만 진짜로 의미 있는 숫자는 아래 판정이다.

## 1×에서 읽히는가 (rogue와 duelist의 idle 0을 바닥 타일 위 1×로 보고, 픽셀 차이 수도 세어서 판정)
머리는 대략 6×4px이고, 머리 옵션 하나가 바꾸는 픽셀은 20~50개다. 그중 머리 모양끼리만 다른 픽셀은 **10~14개**뿐이다.

**확실히 읽힘 (색이나 실루엣이 바뀜)**
- 머리 색: 10개 거의 전부. 1×에서 가장 강한 축이다. 비자연색(teal/violet/rose/lime)과 silver/blonde/black은 바로 구분된다.
- 피부: brown, deep, orc
- 머리 모양: long, bob, mohawk, curly. 실루엣이 머리 밖으로 나간다.
- 머리 장식: hood, wizard, helm, bandana, crown, horns. 사실상 "색 덩어리 + 머리 위 실루엣"으로 읽힌다.
- 얼굴: beard, mask, eyepatch. 이마를 가로지르는 끈 덕분에 eyepatch가 읽힌다.
- 등: cape, scarf
- 옷 팔레트 9개, 무늬 twotone

**약함 (~)**: 직업이나 색에 따라 읽히거나, "뭔가 있다" 정도로만 읽힘
- bald: 머리색이 피부와 비슷하면 안 보인다.
- ponytail: long과 헷갈린다.
- tan: light와 붙어 있다.
- cap: 실루엣이 helm과 같아서 색으로만 구분된다.
- headband: 1px 선이다.
- quiver, backpack: 갈색이 어두운 옷에 묻힌다.
- trim, sash
- 옷 snow: 원래 색인 크림/파랑과 비슷하다.

**안 읽힘 (X)**: 목록 채우기용이라 빼거나 합쳐야 함
- crop, swept, spiky: original과 서로 10~13px밖에 차이가 없어서 1×에서 같은 머리로 보인다.
- braid: long과 거의 같다.
- pale: light와 구분이 안 된다.
- auburn: chestnut/ginger 사이에 끼어 묻힌다. ginger는 warrior 기본 머리색과 같다.
- glasses: 1×에서 eyepatch와 헷갈리고, 어두운 피부에서는 사라진다.
- moustache: 1~4px이고 warrior는 원래 수염이 있다.
- scar: 2px

참고로 업스트림 **사망 프레임 8~12는 해골**이라 어떤 레이어도 남지 않는다. 이건 SPD 디자인을 그대로 따른 결과이고, co-op에서 시체를 구분하려면 이름표가 필요하다.

## 게임에 넣으려면 바꿔야 할 것
1. **생성기**: 기존 방식처럼 스타일×색 조합을 행마다 구우면 머리만 11×11=121행이 되고, 거기에 7 tier×21프레임이 곱해진다. 그러니 이 시안처럼 **원래 색으로 레이어를 한 번만 굽고 런타임에 재채색**해야 한다. 재채색은 정확한 원본 색 → 톤 → 옵션 램프 LUT다(`sheet.json`의 `sourceRamps`, `recolourChannels`). `avatar_layers.py`를 `generate-avatars.py` 자리에 넣고, 7 tier와 21프레임 adventure 아틀라스까지 넓혀야 한다. tier 2~5 금속 갑옷에는 옷 재채색 표가 따로 필요하다.
2. **카탈로그**: `accessories` 하나를 `skinTone`, `pattern`, `headgear`, `face`, `back` 5개 필드로 나누고, `normalizeAppearance`, 서버 저장, 네트워크 동기화 필드를 늘린다. 모자별 `hairClip`과 그리는 순서도 카탈로그에 넣는다.
3. **합성**: 매 프레임 레이어 9장을 겹치지 말고, 외형이 바뀔 때 플레이어마다 한 번만 18(21)프레임 텍스처로 합성한다. 웹은 canvas, 자바는 Pixmap을 쓰고, 이후에는 HeroSprite가 기존처럼 텍스처 하나만 그린다. 합성 규칙: 몸 뒤 → base → cloth → 무늬 → 몸 앞(목) → 수염 → 머리(모자 hairClip 행 위는 지움) → 얼굴 → 모자. 프레임 1은 반전된 좌표계에서 앵커를 잡는다(기존과 같음).
4. 망토·화살통 같은 아이템은 눈 앵커에 붙어 있어서 몸통 포즈와 따로 놀 수 있다. 18프레임 모두 확인했고 문제는 없었다. 다만 앞으로 새 포즈를 추가하면 다시 확인해야 한다.

## 출처와 라이선스
- 몸통, 얼굴, 원래 머리 픽셀: `core/src/main/assets/sprites/{warrior,mage,rogue,huntress,duelist,cleric}.png`, © Oleg Dolya, Evan Debenham, GPL-3.0-or-later. preview의 하수도 타일(`tiles_sewers.png`)과 쥐(`rat.png`)도 같은 출처·라이선스다.
- glasses, headband, cap 맵과 black/chestnut/auburn/silver 머리 램프: 이 저장소의 `generate-avatars.py`에서 복사했다(GPL-3.0-or-later). ginger 램프는 업스트림 warrior 머리색이다.
- 그 밖의 모든 맵(SKULL, 머리 모양 9종, 모자 6종, 얼굴 5종, 등 4종)과 팔레트: 이 시안을 위해 새로 손으로 찍은 픽셀이다. 프로젝트 라이선스에 맞춰 GPL-3.0-or-later로 둔다. AI 도구(Claude)로 작성했다.
- preview 라벨 글꼴: GNU Unifont(`/usr/share/fonts/opentype/unifont`, OFL-1.1 / GPL-2.0+ font exception). preview에만 쓰고 sheet에는 들어가지 않는다.

## 장점과 단점
**장점**
- SPD 원본과 완전히 같은 해상도·광원·팔레트 톤이라 몬스터와 타일 옆에 세워도 이질감이 없다(preview 4번).
- 기존 생성기와 같은 기법이라 도입 비용이 작다. 모든 애니메이션 프레임을 자동으로 따라간다.
- 색 축(머리색, 옷, 피부 일부)과 큰 모자는 1×에서도 확실히 개성을 준다. co-op에서 4명을 구분하기에는 충분하다.

**단점**
- 머리가 6×4px뿐이라 "모양" 축은 4~5개만 살아남는다. 옵션 이름이 많아도 실제로 구분되는 수는 절반 정도다.
- 12px 폭을 몸이 거의 채우고 있어서 등 장식은 몸 앞에 덧그려야 하고, 망토와 배낭은 뒤쪽 팔을 가린다.
- 직업 원래 머리를 SKULL 맵으로 지워야 해서 warrior 수염, mage 수염 같은 직업 정체성이 일부 사라진다.
- 사망 프레임은 해골이라 커스터마이즈가 남지 않는다.
- 몸 비율은 바꿀 수 없다(키, 체형, 성별 표현이 모두 업스트림 그대로).
