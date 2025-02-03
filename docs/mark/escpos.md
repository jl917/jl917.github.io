# ESC/POS 프린터 명령어 문서

## 기본 제어 문자
- `LF (\x0a)`: 라인 피드 (줄바꿈)
- `FS (\x1c)`: 필드 구분자 
- `FF (\x0c)`: 폼 피드 (페이지 넘김)
- `GS (\x1d)`: 그룹 구분자
- `DLE (\x10)`: 데이터 링크 이스케이프
- `EOT (\x04)`: 전송 종료
- `NUL (\x00)`: Null 문자
- `ESC (\x1b)`: 이스케이프 시퀀스 시작
- `TAB (\x74)`: 탭
- `EOL (\n)`: 줄 끝

## 피드 제어 시퀀스
- `CTL_LF (\x0a)`: 인쇄 후 줄바꿈
- `CTL_GLF (\x4a\x00)`: 줄 간격 없이 인쇄 및 용지 공급
- `CTL_FF (\x0c)`: 폼 피드
- `CTL_CR (\x0d)`: 캐리지 리턴
- `CTL_HT (\x09)`: 수평 탭
- `CTL_VT (\x0b)`: 수직 탭

## 문자 간격
- `CS_DEFAULT (\x1b\x20\x00)`: 기본 문자 간격으로 설정
- `CS_SET (\x1b\x20)`: 문자 간격 설정

## 줄 간격
- `LS_DEFAULT (\x1b\x32)`: 기본 줄 간격으로 설정
- `LS_SET (\x1b\x33)`: 줄 간격 설정

## 하드웨어 제어
- `HW_INIT (\x1b\x40)`: 버퍼 데이터 삭제 및 모드 초기화
- `HW_SELECT (\x1b\x3d\x01)`: 프린터 선택
- `HW_RESET (\x1b\x3f\x0a\x00)`: 프린터 하드웨어 리셋

## 금전함 제어
- `CD_KICK_2 (\x1b\x70\x00\x19\x78)`: 2번 핀으로 펄스 신호 전송
- `CD_KICK_5 (\x1b\x70\x01\x19\x78)`: 5번 핀으로 펄스 신호 전송

## 여백 설정
- `BOTTOM (\x1b\x4f)`: 하단 여백 설정
- `LEFT (\x1b\x6c)`: 좌측 여백 설정
- `RIGHT (\x1b\x51)`: 우측 여백 설정

## 용지 컷팅
- `PAPER_FULL_CUT (\x1d\x56\x00)`: 용지 완전 절단
- `PAPER_PART_CUT (\x1d\x56\x01)`: 용지 부분 절단
- `PAPER_CUT_A (\x1d\x56\x41)`: A 타입 부분 절단
- `PAPER_CUT_B (\x1d\x56\x42)`: B 타입 부분 절단
- `STAR_FULL_CUT (\x1B\x64\x02)`: STAR 프린터용 완전 절단

## 텍스트 포맷
### 기본 포맷
- `TXT_NORMAL (\x1b\x21\x00)`: 일반 텍스트
- `TXT_2HEIGHT (\x1b\x21\x10)`: 2배 높이 텍스트
- `TXT_2WIDTH (\x1b\x21\x20)`: 2배 너비 텍스트
- `TXT_4SQUARE (\x1b\x21\x30)`: 2배 높이 및 너비 텍스트
- `STAR_TXT_EMPHASIZED (\x1B\x45)`: STAR 프린터용 강조 텍스트
- `STAR_CANCEL_TXT_EMPHASIZED (\x1B\x46)`: STAR 프린터용 강조 취소

### 텍스트 스타일
- `TXT_UNDERL_OFF (\x1b\x2d\x00)`: 밑줄 해제
- `TXT_UNDERL_ON (\x1b\x2d\x01)`: 1점 밑줄 설정
- `TXT_UNDERL2_ON (\x1b\x2d\x02)`: 2점 밑줄 설정
- `TXT_BOLD_OFF (\x1b\x45\x00)`: 굵은 글씨 해제
- `TXT_BOLD_ON (\x1b\x45\x01)`: 굵은 글씨 설정
- `TXT_ITALIC_OFF (\x1b\x35)`: 이탤릭체 해제
- `TXT_ITALIC_ON (\x1b\x34)`: 이탤릭체 설정

### 글꼴 설정
- `TXT_FONT_A (\x1b\x4d\x00)`: A 글꼴
- `TXT_FONT_B (\x1b\x4d\x01)`: B 글꼴
- `TXT_FONT_C (\x1b\x4d\x02)`: C 글꼴

### 정렬
- `TXT_ALIGN_LT (\x1b\x61\x00)`: 왼쪽 정렬
- `TXT_ALIGN_CT (\x1b\x61\x01)`: 가운데 정렬
- `TXT_ALIGN_RT (\x1b\x61\x02)`: 오른쪽 정렬

### STAR 프린터 정렬
- `STAR_TXT_ALIGN_LA (\x1B\x1D\x61\x00)`: 왼쪽 정렬
- `STAR_TXT_ALIGN_CA (\x1B\x1D\x61\x01)`: 가운데 정렬
- `STAR_TXT_ALIGN_RA (\x1B\x1D\x61\x02)`: 오른쪽 정렬

## 바코드 포맷
### 텍스트 위치
- `BARCODE_TXT_OFF (\x1d\x48\x00)`: HRI 문자 표시 안함
- `BARCODE_TXT_ABV (\x1d\x48\x01)`: HRI 문자 위쪽 표시
- `BARCODE_TXT_BLW (\x1d\x48\x02)`: HRI 문자 아래쪽 표시
- `BARCODE_TXT_BTH (\x1d\x48\x03)`: HRI 문자 위아래 표시

### 바코드 글꼴
- `BARCODE_FONT_A (\x1d\x66\x00)`: A 글꼴
- `BARCODE_FONT_B (\x1d\x66\x01)`: B 글꼴

### 바코드 타입
- `BARCODE_UPC_A (\x1d\x6b\x00)`: UPC-A
- `BARCODE_UPC_E (\x1d\x6b\x01)`: UPC-E
- `BARCODE_EAN13 (\x1d\x6b\x02)`: EAN13
- `BARCODE_EAN8 (\x1d\x6b\x03)`: EAN8
- `BARCODE_CODE39 (\x1d\x6b\x04)`: CODE39
- `BARCODE_ITF (\x1d\x6b\x05)`: ITF
- `BARCODE_NW7 (\x1d\x6b\x06)`: NW7
- `BARCODE_CODE93 (\x1d\x6b\x48)`: CODE93
- `BARCODE_CODE128 (\x1d\x6b\x49)`: CODE128

## 2D 코드 포맷
### 타입
- `TYPE_PDF417`: PDF417 바코드
- `TYPE_DATAMATRIX`: Data Matrix
- `TYPE_QR`: QR 코드

### QR 코드 오류 수정 레벨
- `QR_LEVEL_L`: 7% 오류 수정
- `QR_LEVEL_M`: 15% 오류 수정
- `QR_LEVEL_Q`: 25% 오류 수정
- `QR_LEVEL_H`: 30% 오류 수정

## 이미지 포맷
- `S_RASTER_N`: 일반 크기 래스터 이미지
- `S_RASTER_2W`: 2배 너비 래스터 이미지
- `S_RASTER_2H`: 2배 높이 래스터 이미지
- `S_RASTER_Q`: 4배 크기 래스터 이미지

## 비트맵 포맷
- `BITMAP_S8`: 8-bit 단일 밀도
- `BITMAP_D8`: 8-bit 이중 밀도
- `BITMAP_S24`: 24-bit 단일 밀도
- `BITMAP_D24`: 24-bit 이중 밀도

## 색상 설정
- `0 (\x1b\x72\x00)`: 검정색
- `1 (\x1b\x72\x01)`: 빨간색
- `REVERSE (\x1dB1)`: 색상 반전 (흰색 텍스트, 검은 배경)
- `UNREVERSE (\x1dB0)`: 색상 반전 해제 (검은 텍스트, 흰색 배경)

