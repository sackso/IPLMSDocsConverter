### 설계 문서: IPLMS Office Converter (GUI & Daemon Hybrid Ver.)

#### 1. 개요

본 프로그램은 지정된 폴더 내의 다양한 오피스 문서(MS Office, 한컴오피스)를 주기적으로 변환하는 Java 기반의 유틸리티입니다. 기존 데몬(Daemon) 방식에 더해, 사용자 인터페이스(GUI)를 통해 서비스의 실행 및 종료를 제어하고 실시간 로그를 확인할 수 있는 하이브리드 형태로 개발되었으며, 특정 단일 문서에 대해 즉각적인 변환을 요청할 수 있는 내장 HTTP REST API를 제공합니다.

**주요 기능:**
- **GUI 기반 제어**: 800x600 크기의 창을 통해 서비스 실행/종료 및 로그 확인이 가능합니다.
- **실시간 로그 출력**: 콘솔 출력 메시지를 GUI의 텍스트 영역에 실시간으로 표시하며, 스크롤을 통해 이전 로그를 확인할 수 있습니다.
- **로그 지우기 기능**: GUI의 버튼을 통해 텍스트 영역의 모든 로그를 지울 수 있습니다.
- **서비스 실행/종료**: "실행" 버튼으로 변환 서비스를 시작하고, "종료" 버튼으로 서비스를 안전하게 중단합니다. (이때 내장 HTTP 서버도 동시 시작/종료됩니다.)
- **실시간 단일 파일 변환 API**: 외부 시스템이나 스크립트에서 HTTP (GET/POST) 요청을 통해 원본 파일의 절대 경로를 전달하면 즉시 PDF 변환 및 텍스트 추출을 수행하고 JSON 결과를 응답합니다.
- **안전한 동시성 제어 (Mutex Lock)**: 정기 데몬 변환 작업과 REST API를 통한 실시간 변환 작업이 동시에 실행되더라도 LibreOffice 경합이 발생하지 않도록 `ReentrantLock`으로 상호 배제(Mutex) 처리를 수행합니다.
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

1.  **GUI (ConverterGUI)**:
    *   `JFrame` 기반의 메인 윈도우 (800x600).
    *   `JTextPane` (`JScrollPane` 포함) 및 `StyledDocument`를 통해 콘솔 메시지를 수신하며, 대괄호 태그별로 색상을 다르게 파싱하여 출력합니다.
    *   "실행", "종료", "로그 지우기" 버튼을 포함합니다.
    *   애플리케이션의 시작점(Entry Point) 역할을 합니다.
2.  **콘솔 출력 리다이렉션**: `PipedOutputStream`과 `PipedInputStream`을 사용하여 `System.out` 및 `System.err`의 출력을 캡처하고, 별도의 스레드에서 라인 단위로 읽어 정규식 파싱을 통해 `JTextPane`에 안전하게 추가합니다.
3.  **메인 스케줄러 (Main Scheduler)**: `ScheduledExecutorService`를 사용하여 `ConverterMain.runConversionCycle()`을 주기적으로 호출합니다. 이 스케줄러는 GUI의 "실행" 및 "종료" 버튼에 의해 제어됩니다.
4.  **변환 주기 실행기 (Conversion Cycle Runner)**: `ConverterMain.runConversionCycle()` 메소드
    *   **타임스탬프 생성**: `yyyyMMddHHmm` 형식의 타임스탬프를 생성하여 해당 주기의 고유 ID로 사용합니다.
    *   **출력 폴더 생성**: 기본 출력 폴더 내에 타임스탬프 이름의 하위 폴더를 생성합니다.
    *   **메모리 검사**: `checkMemoryAndExitIfNeeded()`를 호출하여 메모리 상태를 확인하고 필요 시 조치합니다.
    *   **파일 스캐너**: 입력 폴더에서 변환 대상 파일을 탐색합니다.
    *   **변환 실행기**: `SingleThreadExecutor`를 사용하여 파일들을 순차적으로 변환하고, 결과물을 타임스탬프 폴더에 저장합니다.
    *   **결과 경로 수집**: 변환 과정에서 생성되는 모든 파일(PDF, TXT, 오류 로그, CSV)의 절대 경로를 리스트에 수집합니다.
5.  **결과물 생성 및 관리**:
    *   **문서 변환 모듈**: PDF 및 TXT 파일을 타임스탬프 폴더 내에 생성합니다.
    *   **리포트 생성기**: CSV 리포트를 타임스탬프 폴더 내에 생성합니다.
    *   **결과 목록 파일 생성기**: `writeResultFileList()` 메소드가 수집된 모든 파일 경로를 `yyyyMMddHHmm_result.txt` 파일로 기본 출력 폴더에 저장합니다.
6.  **내장 웹 서버 (HttpServer)**: 
    *   Java 기본 내장 `com.sun.net.httpserver.HttpServer`를 활용합니다.
    *   `/api/convert` 컨텍스트 경로로 등록되어 특정 파일 경로를 전달받아 즉각적인 단일 파일 변환 서비스를 제공합니다.
    *   GUI의 "실행" 및 "종료" 제어 이벤트와 생명주기가 동기화되어 가동 및 정지됩니다.
7.  **동시성 제어 락 (ReentrantLock)**:
    *   단일 JVM 내에서 정기 배치 변환과 실시간 REST API 변환 요청이 동시에 발생해 발생하는 LibreOffice 경합 및 잠금 충돌을 예방하기 위해 `ReentrantLock` 상호 배제(Mutex) 메커니즘을 사용합니다.
8.  **문서 변환 및 텍스트 추출 상세 흐름 (Diagram)**:
    *   원본 문서(MS Office, 한컴오피스 HWP/HWPX)가 LibreOffice Engine을 통과하여 PDF 파일로 변환된 후, Apache PDFBox를 통해 텍스트로 추출되는 전체 흐름입니다.

    ```mermaid
    graph LR
        %% 1단계: Source Documents (Input)
        subgraph Source["1. Source Documents (Input)"]
            direction TB
            MS[MS Office 문서<br/>.docx / .doc<br/>.xlsx / .xls<br/>.pptx / .ppt]
            HWP[한컴오피스 문서<br/>.hwp / .hwpx]
        end

        %% 2단계: LibreOffice Engine (Processing)
        subgraph Engine["2. LibreOffice Engine (Processing)"]
            direction TB
            Cond{확장자 판별<br/>detectFileVersion}
            
            CmdMS["soffice --headless<br/>--convert-to pdf<br/>--outdir &lt;output_dir&gt; &lt;src_file&gt;"]
            
            CmdHWP["soffice --headless<br/>--infilter=Hwp2002_File<br/>--convert-to pdf:writer_pdf_Export<br/>--outdir &lt;output_dir&gt; &lt;src_file&gt;"]
            
            Cond -->|MS Office 계열| CmdMS
            Cond -->|HWP / HWPX 계열| CmdHWP
        end

        %% 3단계: Target Output & Text Extraction
        subgraph Dest["3. Target Output & Text Extraction"]
            direction TB
            PDF["변환 완료 PDF 파일<br/>(*.pdf)"]
            
            PDFBox["Apache PDFBox 라이브러리<br/>PDDocument.load(pdfFile)<br/>& PDFTextStripper"]
            
            TXT["추출 완료 텍스트 파일<br/>(*.txt, UTF-8)"]
            
            PDF --> PDFBox
            PDFBox --> TXT
        end

        %% 흐름 연결
        MS --> Cond
        HWP --> Cond
        
        CmdMS --> PDF
        CmdHWP --> PDF
        
        %% 스타일 지정
        classDef source fill:#e1f5fe,stroke:#01579b,stroke-width:2px;
        classDef engine fill:#e8f5e9,stroke:#1b5e20,stroke-width:2px;
        classDef dest fill:#fff3e0,stroke:#e65100,stroke-width:2px;
        
        class MS,HWP source;
        class Cond,CmdMS,CmdHWP engine;
        class PDF,PDFBox,TXT dest;
    ```

---

#### 3. 핵심 프로세스

##### 3.1. 프로그램 시작 (GUI-mode)
- `ConverterGUI`의 `main` 메소드가 실행되어 GUI 창을 띄웁니다.
- GUI 생성자에서 `ConverterMain.loadProperties()`를 호출하여 설정 파일을 한 번 로드합니다.
- "실행" 버튼 클릭 시, `ScheduledExecutorService`가 `ConverterMain.runConversionCycle`을 주기적으로 실행하도록 예약하고, `ConverterMain.startHttpServer()`를 호출하여 내장 HTTP 서버를 시작합니다.
- "종료" 버튼 클릭 시, `ScheduledExecutorService`를 안전하게 종료하고, `ConverterMain.stopHttpServer()`를 호출하여 내장 HTTP 서버를 중단합니다.

##### 3.2. 변환 주기 (`runConversionCycle`)
1.  **타임스탬프 및 폴더 생성**: `yyyyMMddHHmm` 형식의 타임스탬프를 기반으로 이번 주기의 결과물을 저장할 고유 폴더를 생성합니다.
2.  **메모리 확인**: `checkMemoryAndExitIfNeeded()`를 호출하여 메모리 상태를 점검합니다.
3.  **파일 탐색**: `scanDirectory()`를 통해 변환할 파일 목록을 수집합니다.
4.  **순차 변환**: 각 파일에 대해 다음을 수행합니다.
    *   결과물(PDF, TXT)이 저장될 경로를 타임스탬프 폴더로 지정합니다.
    *   `conversionLock.lock()`을 획득하여 LibreOffice 변환 작업을 진행합니다.
    *   변환을 시도하고, 성공/실패 여부와 관계없이 결과 파일 경로를 `resultFilePaths` 리스트에 추가합니다.
    *   오류 발생 시, 오류 로그(`_ERR.txt`)도 타임스탬프 폴더에 생성하고 해당 경로를 리스트에 추가합니다.
    *   작업이 완료되면 `finally`에서 `conversionLock.unlock()`을 보장합니다.
5.  **리포트 생성**:
    *   `generateCsvReport()`를 호출하여 CSV 리포트를 타임스탬프 폴더에 생성하고, 해당 파일 경로를 리스트에 추가합니다.
    *   `writeResultFileList()`를 호출하여 수집된 모든 경로를 `yyyyMMddHHmm_result.txt` 파일로 최종 저장합니다.

##### 3.3. 실시간 단일 파일 변환 (REST API 호출)
1.  **API 요청 접수**: 외부 클라이언트로부터 `GET` 또는 `POST` 메소드로 `/api/convert?filePath=...` 요청이 인입됩니다. (POST의 경우 JSON body `{"filePath":"..."}` 형태도 지원)
2.  **매개변수 및 유효성 검증**: 요청 파라미터 `filePath`가 정상적으로 제공되었는지 확인하고, 파일 존재 여부 및 변환 대상 확장자(`hwp`, `docx` 등) 여부를 검증합니다.
3.  **상호 배제 락(Mutex Lock) 획득**: `conversionLock.lock()`을 호출하여 현재 실행 중인 다른 변환(배치 또는 다른 API 요청)이 끝날 때까지 대기하거나 락을 획득합니다.
4.  **즉시 변환 수행**:
    *   원본 파일과 동일한 경로에 PDF 및 TXT 결과물이 저장되도록 설정합니다.
    *   `convertToPdf()` 및 `extractTextFromPdf()`를 호출하여 즉각적으로 변환 및 텍스트 추출을 완료합니다.
5.  **결과 응답 및 락 해제**:
    *   변환 성공 시 JSON 형식으로 PDF/TXT 절대 경로, 텍스트 추출 성공 여부, **추출된 텍스트 내용**, 소요시간을 반환하고 실패 시 오류 메시지를 반환합니다.
    *   `finally` 블록에서 `conversionLock.unlock()`을 보장하여 대기 중인 다른 작업들이 진행될 수 있도록 처리합니다.

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

- **`ConverterGUI` 클래스 (신규)**:
    - `JFrame`을 상속받아 GUI를 구성합니다.
    - `JTextPane consoleOutputArea`: 콘솔 출력을 표시하는 영역으로, 대괄호 태그 영역을 감지하여 알맞은 색상으로 렌더링하는 다중 스타일 문서 컴포넌트입니다.
    - `JButton runButton`, `stopButton`, `clearLogButton`: 서비스 제어 및 로그 관리를 위한 버튼.
    - `redirectSystemOutput()`: `PipedOutputStream` 및 `PipedInputStream`을 사용하여 `System.out`과 `System.err`를 `consoleOutputArea`로 리다이렉션하며, 정규식 `(\[[^\]]+\])` 매칭을 이용해 상태 태그(성공, 오류, 경고 등)마다 적합한 색상(초록, 빨강, 주황 등)을 자동 적용하여 추가합니다.
    - `startService()`: "실행" 버튼 클릭 시 호출되며, `ScheduledExecutorService` 및 내장 웹 서버를 구동합니다.
    - `stopService()`: "종료" 버튼 클릭 시 호출되며, 스케줄러를 정지하고 웹 서버를 안전하게 해제합니다.
- **`ConverterMain` 클래스 변경**:
    - `main` 메소드 제거: `ConverterGUI`가 애플리케이션의 시작점이 됩니다.
    - `runConversionCycle()` 메소드 `public static`으로 변경: `ConverterGUI`에서 호출 가능하도록 합니다.
    - `loadProperties()` 메소드 `public static`으로 변경: `ConverterGUI`에서 호출 가능하도록 하며, 내부의 성공 로드 메시지 출력은 제거되었습니다.
    - `getDaemonIntervalMinutes()` 메소드 추가: `daemonIntervalMinutes` 값을 `ConverterGUI`에 제공합니다.
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