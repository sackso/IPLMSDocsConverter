### 설계 문서: IPLMS Office Converter

#### 1. 개요

본 프로그램은 지정된 폴더 내의 다양한 오피스 문서(MS Office, 한컴오피스)를 PDF 파일로 변환하고, 변환된 PDF에서 텍스트를 추출하여 별도의 텍스트 파일로 저장하는 Java 기반의 배치(Batch) 유틸리티입니다. 전체 변환 과정의 결과를 담은 CSV 리포트를 생성하여 작업 내역을 추적할 수 있도록 지원합니다.

주요 기능은 다음과 같습니다.
- 지정된 입력 폴더 및 하위 폴더의 모든 문서를 재귀적으로 탐색
- 지원 포맷: `docx`, `doc`, `xlsx`, `xls`, `pptx`, `ppt`, `hwpx`, `hwp`
- LibreOffice를 활용하여 문서를 PDF로 변환
- Apache PDFBox를 활용하여 PDF에서 텍스트 추출
- 개별 파일 변환에 대한 타임아웃(Timeout) 설정 및 처리
- 변환 성공/실패, 소요 시간 등을 포함한 종합 결과 리포트(CSV) 생성

---

#### 2. 시스템 아키텍처

프로그램은 다음과 같은 구성 요소와 흐름으로 동작합니다.

1.  **설정 로더 (Property Loader)**: `config.properties` 파일에서 LibreOffice 경로, 입/출력 폴더 등의 설정을 로드합니다.
2.  **파일 스캐너 (File Scanner)**: 설정된 입력 폴더를 재귀적으로 탐색하여 변환 대상 파일 목록을 생성합니다.
3.  **변환 실행기 (Conversion Executor)**:
    *   `SingleThreadExecutor`를 사용하여 파일 목록을 하나씩 순차적으로 처리합니다. 이는 LibreOffice 프로세스와의 충돌을 방지하고 시스템 안정성을 확보하기 위함입니다.
    *   각 파일의 변환 작업은 별도의 `Callable` 태스크로 정의되며, 타임아웃이 적용된 `Future.get()`을 통해 실행됩니다.
4.  **문서 변환 모듈 (Document Converter)**:
    *   **PDF 변환**: `ProcessBuilder`를 통해 LibreOffice의 Command Line Interface(CLI)를 호출하여 문서를 PDF로 변환합니다.
    *   **텍스트 추출**: 변환된 PDF는 Apache PDFBox 라이브러리를 통해 텍스트 콘텐츠가 추출되어 `.txt` 파일로 저장됩니다.
5.  **결과 수집기 (Result Collector)**: 각 파일의 변환 결과(성공/실패, 소요 시간 등)는 `ReportRow` 객체에 담겨 Thread-Safe 한 `ConcurrentLinkedQueue`에 안전하게 수집됩니다.
6.  **리포트 생성기 (Report Generator)**: 모든 파일 처리가 완료된 후, 수집된 결과 데이터를 바탕으로 최종 CSV 리포트를 생성합니다.

**외부 의존성:**
*   **LibreOffice**: 실제 문서 파일을 PDF로 변환하는 핵심 엔진. 시스템에 설치되어 있어야 합니다.
*   **Apache PDFBox**: PDF 파일의 텍스트를 추출하기 위한 Java 라이브러리.

---

#### 3. 핵심 프로세스

##### 3.1. 설정 로딩
- 프로그램 시작 시 `loadProperties()` 메소드가 실행됩니다.
- 실행 파일(.jar)과 동일한 경로에 위치한 `config.properties` 파일을 읽어 주요 설정 값을 초기화합니다.
- 설정 파일이 없거나 특정 값이 누락된 경우, 코드에 하드코딩된 기본값(Default)이 사용됩니다.

##### 3.2. 파일 탐색
- `scanDirectory()` 메소드는 `converter.input.dir`로 지정된 폴더를 시작으로 모든 하위 폴더를 재귀적으로 탐색합니다.
- 파일 확장자를 기준으로 지원하는 오피스 문서만 필터링하여 `List<File>` 컬렉션에 추가합니다.

##### 3.3. 문서 변환 (순차 처리)
- `main` 메소드 내에서 `Executors.newSingleThreadExecutor()`를 통해 단일 스레드 풀을 생성합니다.
- 탐색된 파일 목록을 순회하며 각 파일을 처리하는 `Callable` 태스크를 생성하여 스레드 풀에 제출합니다.
- `future.get(timeoutSeconds, TimeUnit.SECONDS)`를 호출하여 `config.properties`에 설정된 시간 동안 변환 완료를 대기합니다.
    - **(성공)**: 시간 내에 변환이 완료되면 다음 파일로 넘어갑니다.
    - **(타임아웃)**: 지정된 시간을 초과하면 `TimeoutException`이 발생하고, 해당 파일은 '실패(타임아웃)'로 기록된 후 다음 파일 처리를 계속합니다.
    - **(기타 예외)**: 변환 중 다른 예외가 발생해도 프로그램은 중단되지 않고, 해당 파일은 '실패(에러)'로 기록된 후 다음으로 넘어갑니다.

##### 3.4. 텍스트 추출
- `extractTextFromPdf()` 메소드는 PDF 변환이 성공한 경우에만 호출됩니다.
- Apache PDFBox의 `PDDocument.load()`로 PDF 파일을 열고, `PDFTextStripper`를 이용해 전체 텍스트를 추출합니다.
- 추출된 텍스트는 원본 파일명과 동일한 이름의 `.txt` 파일로 저장됩니다. (인코딩: UTF-8)

##### 3.5. 결과 리포팅
- 모든 파일의 변환 시도가 완료된 후, `generateCsvReport()` 메소드가 호출됩니다.
- `reportQueue`에 누적된 모든 `ReportRow` 데이터를 순회하며 CSV 형식의 문자열을 생성합니다.
- `번호, 파일경로, 파일명, 파일종류, pdf변환결과, 텍스트추출결과, 파일용량(KB), 소요시간(초)` 형식의 헤더와 데이터를 포함한 `conversion_report.csv` 파일을 출력 폴더에 생성합니다. (인코딩: UTF-8 with BOM)

---

#### 4. 데이터 모델

- **`ReportRow` (private static class)**: 단일 파일의 변환 결과를 저장하기 위한 내부 클래스입니다.
    - `filePath` (String): 파일의 전체 경로
    - `fileName` (String): 파일명
    - `fileType` (String): 파일 종류 및 버전 (예: DOCX (2016/365))
    - `pdfResult` (String): PDF 변환 결과 ("성공", "실패", "실패 (타임아웃)")
    - `txtResult` (String): 텍스트 추출 결과 ("성공", "실패")
    - `elapsedTime` (String): 변환에 소요된 시간 (초)
    - `fileSize` (String): 파일 크기 (KB)

---

#### 5. 오류 처리

- **변환 실패**: LibreOffice 프로세스가 0이 아닌 종료 코드를 반환할 경우, 변환은 실패로 간주되고 리포트에 '실패'로 기록됩니다.
- **타임아웃**: `future.get()` 대기 시간을 초과하면, 진행 중인 태스크는 강제 취소(`future.cancel(true)`)되고 리포트에 '실패 (타임아웃)'으로 기록됩니다.
- **오류 로그**: 변환 중 예외 발생 또는 타임아웃 시, `writeErrorFile()` 메소드가 호출되어 원본 파일명에 `_ERR.txt` 접미사가 붙은 오류 로그 파일을 생성합니다. 이 파일에는 오류 발생 시각과 상세 원인이 기록됩니다.
- **안정성**: 모든 예외는 개별 파일 수준에서 처리되므로, 특정 파일의 변환 실패가 전체 배치 작업을 중단시키지 않습니다.

---

#### 6. 상세 기능 명세

- **`main(String[] args)`**: 프로그램의 진입점. 전체 프로세스를 관장하고 조율합니다.
- **`loadProperties()`**: `config.properties` 파일을 로드하여 전역 변수(`libreOfficePath`, `inputDirSetting` 등)를 초기화합니다.
- **`scanDirectory(File dir, List<File> resultList)`**: 재귀적으로 디렉토리를 탐색하여 대상 파일을 찾습니다.
- **`convertToPdf(File srcFile, File destPdf, String fileVersion)`**: `ProcessBuilder`를 사용해 LibreOffice CLI를 실행하고 PDF 변환을 수행합니다. 한컴오피스 문서(`hwp`, `hwpx`)의 경우, 전용 `infilter` 옵션을 추가하여 호환성을 높입니다.
- **`detectFileVersion(File file, String ext)`**: 파일의 내부 구조(XML 메타데이터 또는 바이너리 헤더)를 분석하여 MS Office 버전, HWP 버전 등을 식별합니다. 이는 리포트의 정확성을 높이기 위한 부가 기능입니다.
- **`extractTextFromPdf(File pdfFile, File destTxt)`**: PDFBox 라이브러리를 사용하여 PDF 파일에서 텍스트를 추출하고 `.txt` 파일로 저장합니다.
- **`generateCsvReport(File exportFolder)`**: `reportQueue`의 모든 작업 결과를 종합하여 최종 CSV 리포트 파일을 생성합니다.
- **`writeErrorFile(File srcFile, String errMsg, File targetDir)`**: 변환 실패 시 상세한 오류 내용이 담긴 별도의 텍스트 파일을 생성합니다.