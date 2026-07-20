### 설계 문서: IPLMS Office Converter (Daemon Ver.)

#### 1. 개요

본 프로그램은 지정된 폴더 내의 다양한 오피스 문서(MS Office, 한컴오피스)를 주기적으로 변환하는 Java 기반의 데몬(Daemon) 유틸리티입니다. 설정된 시간 간격마다 지정된 폴더를 탐색하여 문서를 PDF로 변환하고, 텍스트를 추출하여 저장합니다. 전체 변환 과정의 결과는 CSV 리포트로 생성됩니다.

**주요 기능:**
- **데몬 실행**: 백그라운드에서 상시 실행되며, 설정된 주기(예: 10분)마다 변환 작업을 자동으로 수행합니다.
- **주기적 파일 탐색**: 지정된 입력 폴더 및 하위 폴더의 모든 문서를 재귀적으로 탐색합니다.
- **지원 포맷**: `docx`, `doc`, `xlsx`, `xls`, `pptx`, `ppt`, `hwpx`, `hwp`
- **PDF 변환 및 텍스트 추출**: LibreOffice와 Apache PDFBox를 활용합니다.
- **메모리 관리**: 최대 메모리 사용량을 2GB로 제한하고, 초과 시 GC를 시도하며, 실패 시 안전하게 종료됩니다.
- **안정적인 순차 처리**: 개별 파일 변환에 타임아웃을 적용하고, 오류 발생 시에도 전체 프로세스가 중단되지 않도록 보장합니다.
- **결과 리포팅**: 각 실행 주기마다의 변환 결과를 CSV 파일로 생성합니다.

---

#### 2. 시스템 아키텍처

1.  **메인 스케줄러 (Main Scheduler)**:
    *   `main` 메소드에서 `ScheduledExecutorService`를 사용하여 데몬의 핵심 로직인 `runConversionCycle()`을 주기적으로 호출합니다.
    *   실행 주기는 `config.properties`의 `daemon.interval.minutes` 설정에 따릅니다.
2.  **변환 주기 실행기 (Conversion Cycle Runner)**: `runConversionCycle()` 메소드
    *   **메모리 검사**: 작업 시작 전 `checkMemoryAndExitIfNeeded()`를 호출하여 메모리 사용량을 확인합니다. 2GB 초과 시 GC를 시도하고, 그래도 메모리가 부족하면 시스템 오류 로그를 남기고 프로그램을 종료시킵니다.
    *   **파일 스캐너**: 입력 폴더를 재귀적으로 탐색하여 변환 대상 파일 목록을 생성합니다.
    *   **변환 실행기**: `SingleThreadExecutor`를 사용하여 탐색된 파일들을 하나씩 순차적으로 변환합니다.
3.  **문서 변환 모듈 (Document Converter)**:
    *   **PDF 변환**: `ProcessBuilder`를 통해 LibreOffice CLI를 호출하여 문서를 PDF로 변환합니다.
    *   **텍스트 추출**: Apache PDFBox를 사용하여 PDF에서 텍스트를 추출합니다.
4.  **결과 수집 및 리포팅**:
    *   각 파일의 변환 결과는 `ConcurrentLinkedQueue`에 수집됩니다.
    *   한 주기의 모든 파일 처리가 끝나면, 수집된 결과를 바탕으로 CSV 리포트를 생성합니다.

**외부 의존성:**
*   **LibreOffice**: 문서 변환 엔진.
*   **Apache PDFBox**: PDF 텍스트 추출 라이브러리.

---

#### 3. 핵심 프로세스

##### 3.1. 프로그램 시작 (Daemon-mode)
- `main` 메소드가 실행되면, `loadProperties()`를 통해 설정을 로드합니다.
- `ScheduledExecutorService`가 `runConversionCycle` 메소드를 `daemon.interval.minutes`에 설정된 주기로 반복 실행하도록 예약합니다. 프로그램은 종료되지 않고 계속 실행 상태를 유지합니다.

##### 3.2. 변환 주기 (`runConversionCycle`)
1.  **메모리 확인**: `checkMemoryAndExitIfNeeded()`를 호출하여 현재 힙 메모리 사용량이 2GB를 넘는지 확인합니다.
    *   **초과 시**: `System.gc()`를 호출하여 메모리 회수를 시도합니다.
    *   **GC 실패 시**: GC 후에도 메모리 사용량이 여전히 2GB를 넘으면, `SYSTEM_FATAL_ERROR.txt`에 오류를 기록하고 `System.exit(1)`을 호출하여 프로그램을 즉시 종료합니다.
2.  **파일 탐색**: `scanDirectory()`를 통해 변환할 파일 목록을 수집합니다.
3.  **순차 변환**: `SingleThreadExecutor`를 사용하여 각 파일을 순서대로 처리합니다.
    *   `future.get()`을 사용하여 개별 파일 변환에 타임아웃을 적용합니다.
    *   오류(타임아웃 포함)가 발생한 파일은 실패로 기록되고, 다음 파일 변환은 중단 없이 계속됩니다.
4.  **리포트 생성**: 해당 주기의 작업이 모두 끝나면 `generateCsvReport()`를 호출하여 `conversion_report.csv`를 생성합니다.

##### 3.3. 설정 로딩 (`loadProperties`)
- `daemon.interval.minutes`: 데몬 실행 주기 (분 단위, 기본값 10)가 추가되었습니다.
- 기존의 `converter.input.dir`, `converter.timeout.seconds` 등의 설정도 함께 로드됩니다.

---

#### 4. 데이터 모델

- **`ReportRow` (private static class)**: 이전과 동일하게 단일 파일의 변환 결과 저장.

---

#### 5. 오류 처리 및 안정성

- **메모리 초과 (치명적 오류)**: `checkMemoryAndExitIfNeeded`에서 처리. GC로도 해결되지 않는 메모리 부족은 시스템 전체의 안정성을 위해 즉시 종료로 이어집니다. 오류 내용은 `SYSTEM_FATAL_ERROR.txt`에 기록됩니다.
- **개별 파일 변환 오류**:
    - **타임아웃**: `TimeoutException` 발생 시, 해당 파일은 '실패(타임아웃)'으로 기록됩니다.
    - **프로세스 오류**: LibreOffice 프로세스 오류 발생 시, '실패(에러)'로 기록됩니다.
    - **오류 로그**: 개별 파일의 오류는 기존과 같이 `_ERR.txt` 파일에 상세 내용이 기록됩니다.
- **주기 실행 안정성**: `runConversionCycle` 내부의 모든 로직은 `try-catch` 블록으로 감싸여 있어, 특정 주기에 예기치 않은 오류가 발생하더라도 데몬 스케줄러 자체가 중단되지 않고 다음 주기에 다시 실행을 시도합니다.

---

#### 6. 상세 기능 명세 (변경 및 추가된 부분)

- **`main(String[] args)`**: `ScheduledExecutorService`를 생성하고 `runConversionCycle`을 주기적으로 실행하도록 변경.
- **`runConversionCycle()`**: 한 번의 변환 작업을 수행하는 모든 로직(메모리 검사, 파일 스캔, 변환, 리포팅)을 포함하는 새로운 메소드.
- **`checkMemoryAndExitIfNeeded()`**: 힙 메모리 사용량을 확인하고, 임계값 초과 시 GC를 시도하거나 시스템을 종료하는 기능.
- **`writeSystemErrorFile(String errMsg)`**: 메모리 부족과 같은 시스템 수준의 치명적 오류를 별도의 로그 파일(`SYSTEM_FATAL_ERROR.txt`)에 기록하는 기능.
- **`loadProperties()`**: `daemon.interval.minutes` 설정을 읽어오는 기능 추가.