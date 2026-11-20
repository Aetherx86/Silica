# Silica

JVM 환경의 셀룰러 오토마톤 (Cellular Automata on the JVM)

런타임 의존성 없이 순수 Kotlin으로 작성된 JVM 기반 셀룰러 오토마톤 엔진입니다. 성능 특성이 상이한 세 가지 콘웨이의 라이프 게임(Conway's Game of Life) 엔진과 낙하 모래(falling-sand) 물리 시뮬레이션을 포함합니다.

[English Documentation](README.md)

라이프 게임은 모든 셀이 동시에 상태를 갱신하는 동기식 모델인 반면, 낙하 모래는 물질 보존 법칙을 따르며 입자를 인플레이스로 이동시킵니다. Silica는 상이한 기법이 요구되는 두 모델을 모두 지원합니다.

```text
silica view
silica hashlife --pattern gosper --exponent 100
```

## 라이프 엔진

| 엔진 | 동작 방식 | 주요 용도 |
| --- | --- | --- |
| `LifeGrid` | 임의의 `B/S` 규칙을 지원하는 더블 버퍼 바이트 배열 | 직관적인 기준 동작 구현 및 콘웨이 규칙 이외의 변형 규칙 |
| `BitLife` | 머신 워드당 64개 셀 패킹, 비트 슬라이스 가산기로 이웃 수 계산 | 대규모 고밀도 격자 시뮬레이션 |
| `HashLife` | 무한 평면 상의 메모이제이션 해시 트리 쿼드트리 | 천문학적인 세대 수에 걸친 반복 패턴 고속 연산 |

```kotlin
val life = HashLife()
for ((x, y) in Rle.parse(Patterns.GOSPER_GUN)) life[x.toLong(), y.toLong()] = true
life.advancePowerOfTwo(100)
```

## 벤치마크 성능

Apple Silicon 단일 스레드 기준 측정치:

| 작업 | 결과 |
| --- | --- |
| `BitLife`, 2048 x 2048, 2000 세대 | 단일 스레드 기준 초당 약 2 * 10^10 셀 |
| `HashLife`, Gosper gun, 2^100 세대 | 16 밀리초 |
| `SandWorld`, 512 x 288 데모 씬, 700 프레임 | 초당 약 2500 프레임 |

## 버전 관리

버그 수정 및 보안 패치는 `0.0.X`, 신규 기능 및 플랫폼 지원 추가는 `0.X.0`을 증가시킵니다. 세부 사항은 [CHANGELOG.md](CHANGELOG.md)를 참조하십시오.

## 0.2.0 변경 사항

- 세 엔진 모두 임의의 라이프 계열 `B/S` 규칙을 지원합니다. `BitLife`는 콘웨이 규칙 전용 경로를 유지하고, 그 밖의 규칙은 4비트 비트 슬라이스 이웃 수로 계산합니다. `HashLife`는 무한 평면에서 표현할 수 없는 `B0` 규칙을 거부합니다.
- `Rule.parse`는 기존 `S/B` 표기(예: `23/36`)도 읽고, `Rule.notation`은 정규화된 표기를 돌려줍니다.
- RLE 입출력: `Rle.read`는 헤더의 크기, 규칙, 이름, 주석을 함께 읽고, `Rle.write`는 70자 줄바꿈의 정규화된 RLE를 씁니다. 실행 길이, 좌표, 셀 수에 상한이 있어 악의적인 파일은 오류로 끝납니다.
- Hashlife 질의: 임의 세대 수 진행 `advance(BigInteger)`, `boundingBox()`, 64비트를 넘는 범위에서도 정확한 `populationIn(...)`, `cells()`, `toRle()`.
- 명령줄: `--rle`, `--rule`, `--out`, `--edge`, `--json` 옵션, `--every` 표본 추출, 새 `rle` 및 `bench` 명령. 알 수 없는 옵션은 거부합니다.

```sh
silica hashlife --pattern acorn --generations 5206 --json
silica rle --pattern glider --rule 23/36
silica bench --json
```

## 라이선스

MIT License. 세부 사항은 [LICENSE](LICENSE) 파일을 참조하십시오.
