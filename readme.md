반갑습니다, 선배님. 25년 차 동료 개발자로서 프로젝트의 실제 소스 구성 및 명세 [source: 6]와 일치하도록 누락된 기술 정보들을 전면 보완한 `README.md` 작성했습니다.

제공해주신 프로젝트 명세 [source: 6]를 바탕으로, **실제 지원 포맷(HWP/HWPX, DWG AcCoreConsole), HTTP REST API 사양(포트 9119), 상세 설정 파일(`config.properties`) 구조 및 결과 리포트(CSV, TXT) 처리 방식**까지 정밀하게 보완 반영했습니다.

---

# IPLMS Office & DWG Converter (IPLMS Docs Converter)

본 솔루션은 지정된 폴더 내의 오피스 문서(MS Office, 한컴오피스) 및 **AutoCAD(DWG) 도면**을 자동 변환하고 텍스트를 추출하는 Java 기반 하이브리드 문서 변환 엔진입니다.

---

## 🚀 주요 기능

1. **다양한 포맷의 고정밀 PDF 변환**
   - **MS Office**: Word (`.docx`, `.doc`), PowerPoint (`.pptx`, `.ppt`), Excel (`.xlsx`, `.xls`)
   - **한컴오피스**: HWP (v5.0 이상), HWPX (표준 v1.0)
   - **AutoCAD**: DWG (`AcCoreConsole` 연동)
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

## 🛠️ 시스템 요구사항 및 환경 (Prerequisites)

* **Java Runtime**: JDK / JRE 1.8 이상
* **운영체제**: Windows Server 2016 이상 / Windows 10 (Cmd/Batch 스크립트 실행 환경)
* **연동 변환 엔진**:
  * **LibreOffice Portable**: MS Office / HWP 변환용
  * **Autodesk AutoCAD**: `accoreconsole.exe` (AutoCAD 2024 이상 권장)
  * **AutoCAD Script**: `dwg2pdf.scr` 배치 스크립트

---

## 📂 프로젝트 디렉터리 구조 (Directory Structure)

```text
IPLMSDocsConverter/
├── docs/                        # 시스템 상세 설계 및 명세 문서
│   ├── design.md                # 전체 Architecture & 시스템 설계서
│   ├── admin_guide_toc.md       # 관리자 운용 가이드
│   └── func/                    # 단위 기능별 기술 명세서
│       ├── FN-260715-01_HWP_PDF.md
│       ├── FN-260715-02_LibreOffice_PDF.md
│       ├── FN-260715-03_MSOffice_PDF.md
│       ├── FN-260715-04_PDF_Process.md
│       └── FN-260724-01_DWG_PDF.md
├── lib/                         # PDFBox, Commons-IO, Log4j 등 외장 Jar
├── properties/
│   └── config.properties        # 애플리케이션 핵심 설정 파일
├── scripts/
│   └── dwg2pdf.scr              # AutoCAD 변환 처리 스크립트
├── IPLMSDocsDaemon.cmd          # 통합 변환 데몬 및 GUI 실행 스크립트
├── IPLMSOfficeConvert.cmd       # Office 전용 변환 모듈 스크립트
├── java_src_merge.ps1           # 소스 병합 및 배포 지원 PowerShell 스크립트
└── README.md                    # 프로젝트 안내 문서

```

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

## 💻 실행 및 연동 가이드

### 1. GUI 및 데몬 실행

* `IPLMSDocsDaemon.cmd` 스크립트를 실행하여 애플리케이션을 기동합니다.
* 상단 **[실행]** 버튼 클릭 시 백그라운드 데몬 스케줄러와 내장 REST API 서버가 동시에 활성화됩니다.

### 2. REST API 호출 예시

* **엔드포인트**: `http://localhost:9119/api/convert`
* **POST 요청 (JSON Body)**:
```json
{ "filePath": "C:\\IPLMS\\91_input\\sample_doc.docx" }

```


* **성공 응답 (JSON)**:
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



### 3. 소스 병합 및 유틸리티

* 개발 소스 통합 시 루트의 PowerShell 스크립트를 활용합니다.
```powershell
PS C:\IPLMSDocsConverter> .\java_src_merge.ps1

```



---

## 📖 상세 문서 (Documentation)

자세한 아키텍처 및 단위 기능 명세는 `docs/` 디렉터리의 각 문서를 참조하십시오.

* **[시스템 종합 설계서](https://www.google.com/search?q=docs/design.md)**
* **[관리자 운용 가이드](https://www.google.com/search?q=docs/admin_guide_toc.md)**
* **[HWP -> PDF 변환 명세](https://www.google.com/search?q=docs/func/FN-260715-01_HWP_PDF.md)**
* **[LibreOffice 연동 변환 명세](https://www.google.com/search?q=docs/func/FN-260715-02_LibreOffice_PDF.md)**
* **[MS Office 연동 변환 명세](https://www.google.com/search?q=docs/func/FN-260715-03_MSOffice_PDF.md)**
* **[PDF 후처리 및 텍스트 추출 명세](https://www.google.com/search?q=docs/func/FN-260715-04_PDF_Process.md)**
* **[AutoCAD DWG 변환 명세](https://www.google.com/search?q=docs/func/FN-260724-01_DWG_PDF.md)**

```

```