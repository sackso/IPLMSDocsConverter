@echo off
:: ===================================================================
::  [IPLMS Hybrid Converter Engine - 정통 DOS 배치 실행기 (무괄호 가드판)]
:: ===================================================================
title IPLMS Docs Converter Engine
rem chcp 65001 > nul

:: 현재 배치 파일이 실행된 물리적 위치로 작업 디렉토리(user.dir) 강제 동기화
cd /d "%~dp0"

echo ===================================================================
echo  🚀 IPLMS Hybrid Converter 가동 시동을 시작합니다.
echo  📂 현재 실행 위치: %cd%
echo ===================================================================
echo.

:: -------------------------------------------------------------------
:: 📌 [조건 1] Java Home 및 Path 상단 선언
:: -------------------------------------------------------------------
:: 시스템 환경 변수에 이미 잡힌 java를 쓰려면 아래를 비워두시면 됩니다.
:: 예시: set JAVA_HOME=C:\Program Files\Java\jdk1.8.0_291
set JAVA_HOME=

:: 📌 [문법 교정] 괄호 블록을 전면 제거하고 defined 판단 여부에 따라 한 줄로 명확히 처리
if not defined JAVA_HOME goto SYSTEM_JAVA

:: 사용자 지정 JAVA_HOME 경로가 존재할 경우에만 실행하는 단일 명령어 구문
set "PATH=%JAVA_HOME%\bin;%PATH%"
echo ⚙️ [설정 정보] 외부 지정 Java 경로를 우선 연동합니다.
echo    └─ JAVA_HOME: %JAVA_HOME%
goto CHECK_JAVA

:SYSTEM_JAVA
echo ⚙️ [설정 정보] 시스템 기본 환경변수(PATH) 상의 Java 가상머신을 탐색합니다.

:CHECK_JAVA
echo.


:: -------------------------------------------------------------------
:: 📌 [조건 2] Java 실제 실행 가능 여부 체크
:: -------------------------------------------------------------------
java -version >nul 2>&1
if errorlevel 1 goto ERROR_JAVA

echo ✅ [검증 완료] Java 실행 가상머신이 성공적으로 도출되었습니다.
echo -------------------------------------------------------------------
java -version
echo -------------------------------------------------------------------
echo.
goto CHECK_CONFIG

:ERROR_JAVA
echo ❌ [오류] Java 실행 환경(JRE/JDK) 확인 실패!
echo    └─ 원인: 'java' 명령어가 시스템 경로 혹은 지정된 JAVA_HOME 경로에서 발견되지 않았습니다.
echo    └─ 조치: 상단 JAVA_HOME 지정을 확인하거나 Java 설치 상태를 점검하십시오.
echo.
goto ERROR_END


:: -------------------------------------------------------------------
:: 📌 [조건 3] config.properties 파일의 존재 여부 체크
:: -------------------------------------------------------------------
:CHECK_CONFIG
if not exist "config.properties" goto ERROR_CONFIG

echo ✅ [검증 완료] 설정 파일(config.properties)이 감지되었습니다.
echo.
goto CHECK_JAR

:ERROR_CONFIG
echo ❌ [오류] 설정 파일 누락!
echo    └─ 원인: '%cd%\config.properties' 파일이 존재하지 않습니다.
echo    └─ 조치: JAR 파일 및 execute.cmd와 동일한 폴더에 config.properties를 배치하십시오.
echo.
goto ERROR_END


:: -------------------------------------------------------------------
:: 📌 [조건 4] JAR 실행 (동일 폴더 내의 지정 아티팩트 구동)
:: -------------------------------------------------------------------
:CHECK_JAR
if not exist "IPLMSDocsConverter.jar" goto ERROR_JAR

echo 🔄 [가동 개시] 변환 엔진을 구동합니다... (인터럽트 강제 종료: Ctrl + C)
echo ───────────────────────────────────────────────────────────────────
java -Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8 -jar IPLMSDocsConverter.jar
echo ───────────────────────────────────────────────────────────────────
echo.
echo 🏁 [완료] 변환 프로세스 및 대기열 스케줄링 처리가 모두 마무리되었습니다.
echo.
pause
exit /b

:ERROR_JAR
echo ❌ [오류] 실행 대상 JAR 파일 누락!
echo    └─ 원인: '%cd%\IPLMSDocsConverter.jar' 파일이 없습니다.
echo    └─ 조치: 인텔리제이 아티팩트 빌드가 정상 수행되었는지 재확인해 주세요.
echo.
goto ERROR_END


:ERROR_END
echo ───────────────────────────────────────────────────────────────────
echo ⚠️ [가동 중단] 비정상 구동 상태이므로 배치 파일 프로세스를 강제 중지했습니다.
echo ───────────────────────────────────────────────────────────────────
echo.
pause
exit /b