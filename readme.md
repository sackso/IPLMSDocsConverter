# IPLMS Office & DWG Converter (IPLMS Docs Converter)

본 솔루션은 지정된 폴더 내의 오피스 문서(MS Office, 한컴오피스) 및 **AutoCAD(DWG) 도면**을 자동 변환하고 텍스트를 추출하는 Java 기반 하이브리드 문서 변환 엔진입니다.

---

## 🚀 주요 기능

1. **다양한 포맷의 고정밀 PDF 변환**
   - **MS Office**: Word (`.docx`, `.doc`), PowerPoint (`.pptx`, `.ppt`), Excel (`.xlsx`, `.xls`)
   - **한컴오피스**: HWP (v5.0 이상), HWPX (표준 v1.0)
   - **AutoCAD**: DWG (AcCoreConsole 연동)
2. **Apache PDFBox 기반 텍스트 추출**
   - 변환된 PDF에서 텍스트 레이어를 파싱하여 동일한 파일명의 UTF-8 `.txt` 파일로 자동 저장
3. **하이브리드 운영 모드**
   - **GUI 모드 (`ConverterGUI`)**: 직관적인 인터페이스, 실시간 모니터링 로그, 서비스 시작/종료 및 출력 폴더 바로가기 지원
   - **Daemon 모드**: 설정된 주기(`daemon.interval.minutes`)에 맞춰 백그라운드 자동 스캐닝 및 단일 스레드 안전 순차 변환
4. **내장 REST API 서버 지원**
   - 경량 `HttpServer` 기반 `/api/convert` 엔드포인트를 통해 실시간 단일 파일 즉시 변환 처리 (기본 포트: 9119, CORS 지원)
5. **결과 리포트 및 이력 관리**
   - 엑셀 한글 깨짐 방지(UTF-8 BOM) CSV 리포트 (`conversion_report.csv`) 생성
   - 주기별 전체 변환 산출물 경로 목록 (`yyyyMMddHHmm_result.txt`) 작성
   - 변환 오류 시 상세 예외 리포트 (`{파일명}_ERR.txt`) 별도 생성

---

## ⚙️ 주요 설정 (`properties/config.properties`)

```properties
# 입력 및 출력 폴더 설정
converter.input.dir=C:\\IPLMS\\91_input
converter.output.dir=C:\\IPLMS\\92_output

# 변환 타임아웃 설정 (초)
converter.timeout.seconds=90

# LibreOffice & AutoCAD 엔진 경로
converter.libreoffice.path=C:\\IPLMS\\LibreOfficePortable\\LibreOfficePortable.exe
converter.autocad.path=C:\\Program Files\\Autodesk\\AutoCAD 2024\\accoreconsole.exe
converter.autocad.script.path=C:\\IPLMS\\scripts\\dwg2pdf.scr
converter.autocad.timeout.seconds=120

# REST API 서버 포트 및 데몬 실행 주기
converter.server.port=9119
daemon.interval.minutes=10
```

---

## 🛠️ 실행 및 연동 가이드

### 1. GUI 및 데몬 실행
- `IPLMSDocsDaemon.cmd` 스크립트를 실행하여 애플리케이션을 기동합니다.
- 상단 **[실행]** 버튼 클릭 시 백그라운드 데몬 스케줄러와 내장 REST API 서버가 동시에 활성화됩니다.

### 2. REST API 호출 예시
- **엔드포인트**: `http://localhost:9119/api/convert`
- **POST 요청 (JSON Body)**:
  ```json
  { "filePath": "C:\\IPLMS\\91_input\\sample_doc.docx" }
  ```
- **성공 응답 (JSON)**:
  ```json
  {
    "status": "success",
    "pdfPath": "C:\\IPLMS\\92_output\\sample_doc.pdf",
    "txtPath": "C:\\IPLMS\\92_output\\sample_doc.txt",
    "txtExtracted": true,
    "extractedTextContent": "추출된 문서 본문 내용...",
    "elapsedTime": "2.15초"
  }
  ```
