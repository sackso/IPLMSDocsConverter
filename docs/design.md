### 설계 문서: IPLMS Office Converter (Daemon Ver.)

#### 1. 개요

본 프로그램은 지정된 폴더 내의 다양한 오피스 문서(MS Office, 한컴오피스)를 주기적으로 변환하는 Java 기반의 데몬(Daemon) 유틸리티입니다. 설정된 시간 간격마다 지정된 폴더를 탐색하여 문서를 PDF로 변환하고, 텍스트를 추출하여 저장합니다. 전체 변환 과정의 결과는 CSV 리포트와 결과 파일 목록으로 생성됩니다.

**주요 기능:**
- **데몬 실행**: 백그라운드에서 상시 실행되며, 설정된 주기(예: 10분)마다 변환 작업을 자동으로 수행합니다.
- **타임스탬프 기반 출력**: 각 실행 주기마다 `yyyyMMddHHmm` 형식의 폴더를 생성하고 모든 결과물(PDF, TXT, 로그)을 해당 폴더에 저장하여 실행 이력을 분리합니다.
- **주기적 파일 탐색**: 지정된 입력 폴더 및 하위 폴더의 모든 문서를 재귀적으로 탐색합니다.
- **지원 포맷**: `docx`, `doc`, `xlsx`, `xls`, `pptx`, `ppt`, `hwpx`, `hwp`
- **PDF 변환 및 텍스트 추출**: LibreOffice와 Apache PDFBox를 활용합니다.
- **메모리 관리**: 최대 메모리 사용량을 2GB로 제한하고, 초과 시 GC를 시도하며, 실패 시 안전하게 종료됩니다.
- **결과 리포팅**:
    - 각 실행 주기마다의 변환 상세 결과를 CSV 파일로 생성합니다.
    - 해당 주기에 생성된 모든 파일의 전체 경로를 담은 `yyyyMMddHHmm_result.txt` 파일을 생성합니다.

---

#### 2. 시스템 아키텍처

1.  **메인 스케줄러 (Main Scheduler)**: `ScheduledExecutorService`를 사용하여 `runConversionCycle()`을 주기적으로 호출합니다.
2.  **변환 주기 실행기 (Conversion Cycle Runner)**: `runConversionCycle()` 메소드
    *   **타임스탬프 생성**: `yyyyMMddHHmm` 형식의 타임스탬프를 생성하여 해당 주기의 고유 ID로 사용합니다.
    *   **출력 폴더 생성**: 기본 출력 폴더 내에 타임스탬프 이름의 하위 폴더를 생성합니다.
    *   **메모리 검사**: `checkMemoryAndExitIfNeeded()`를 호출하여 메모리 상태를 확인하고 필요 시 조치합니다.
    *   **파일 스캐너**: 입력 폴더에서 변환 대상 파일을 탐색합니다.
    *   **변환 실행기**: `SingleThreadExecutor`를 사용하여 파일들을 순차적으로 변환하고, 결과물을 타임스탬프 폴더에 저장합니다.
    *   **결과 경로 수집**: 변환 과정에서 생성되는 모든 파일(PDF, TXT, 오류 로그, CSV)의 절대 경로를 리스트에 수집합니다.
3.  **결과물 생성 및 관리**:
    *   **문서 변환 모듈**: PDF 및 TXT 파일을 타임스탬프 폴더 내에 생성합니다.
    *   **리포트 생성기**: CSV 리포트를 타임스탬프 폴더 내에 생성합니다.
    *   **결과 목록 파일 생성기**: `writeResultFileList()` 메소드가 수집된 모든 파일 경로를 `yyyyMMddHHmm_result.txt` 파일로 기본 출력 폴더에 저장합니다.

---

#### 3. 핵심 프로세스

##### 3.1. 프로그램 시작 (Daemon-mode)
- `main` 메소드에서 `ScheduledExecutorService`가 `runConversionCycle`을 주기적으로 실행하도록 예약합니다.

##### 3.2. 변환 주기 (`runConversionCycle`)
1.  **타임스탬프 및 폴더 생성**: `yyyyMMddHHmm` 형식의 타임스탬프를 기반으로 이번 주기의 결과물을 저장할 고유 폴더를 생성합니다.
2.  **메모리 확인**: `checkMemoryAndExitIfNeeded()`를 호출하여 메모리 상태를 점검합니다.
3.  **파일 탐색**: `scanDirectory()`를 통해 변환할 파일 목록을 수집합니다.
4.  **순차 변환**: 각 파일에 대해 다음을 수행합니다.
    *   결과물(PDF, TXT)이 저장될 경로를 타임스탬프 폴더로 지정합니다.
    *   변환을 시도하고, 성공/실패 여부와 관계없이 결과 파일 경로를 `resultFilePaths` 리스트에 추가합니다.
    *   오류 발생 시, 오류 로그(`_ERR.txt`)도 타임스탬프 폴더에 생성하고 해당 경로를 리스트에 추가합니다.
5.  **리포트 생성**:
    *   `generateCsvReport()`를 호출하여 CSV 리포트를 타임스탬프 폴더에 생성하고, 해당 파일 경로를 리스트에 추가합니다.
    *   `writeResultFileList()`를 호출하여 수집된 모든 경로를 `yyyyMMddHHmm_result.txt` 파일로 최종 저장합니다.

---

#### 4. 데이터 모델

- **`ReportRow` (private static class)**: 이전과 동일.
- **`resultFilePaths` (List<String>)**: `runConversionCycle` 내의 지역 변수. 해당 주기에 생성된 모든 결과물의 절대 경로를 저장하는 Thread-safe 리스트 (`CopyOnWriteArrayList`).

---

#### 5. 오류 처리 및 안정성

- **메모리 초과 (치명적 오류)**: 이전과 동일. `SYSTEM_FATAL_ERROR.txt`에 로그를 남기고 종료.
- **개별 파일 변환 오류**: 오류 로그 파일(`_ERR.txt`)이 타임스탬프 폴더 내에 생성됩니다.
- **주기 실행 안정성**: 이전과 동일. 특정 주기의 실패가 전체 데몬 실행에 영향을 주지 않습니다.

---

#### 6. 상세 기능 명세 (변경 및 추가된 부분)

- **`runConversionCycle()`**:
    - 타임스탬프 기반의 출력 폴더 생성 로직 추가.
    - 모든 결과 파일 경로를 수집하기 위한 `CopyOnWriteArrayList` 사용.
    - `generateCsvReport`, `writeErrorFile` 호출 시 타임스탬프 폴더를 인자로 전달.
    - `writeResultFileList` 호출 로직 추가.
- **`generateCsvReport(File exportFolder)`**: `File` 객체를 반환하도록 수정되어, 생성된 파일의 경로를 수집할 수 있게 됨.
- **`writeErrorFile(File srcFile, String errMsg, File targetDir)`**: `File` 객체를 반환하도록 수정되어, 생성된 파일의 경로를 수집할 수 있게 됨.
- **`writeResultFileList(File baseOutputDir, String timestamp, List<String> filePaths)`**:
    - 새로운 메소드.
    - `baseOutputDir`에 `timestamp_result.txt` 이름으로 파일을 생성.
    - 인자로 받은 `filePaths` 리스트의 모든 내용을 파일에 기록.