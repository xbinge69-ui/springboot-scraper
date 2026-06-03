@REM ----------------------------------------------------------------------------
@REM Maven Start Up Batch script (Windows)
@REM https://maven.apache.org/wrapper/
@REM ----------------------------------------------------------------------------
@IF "%__MVNW_ARG0_NAME__%"=="" (SET "MVN_CMD=mvn") ELSE (SET "MVN_CMD=%__MVNW_ARG0_NAME__%")
@SET MAVEN_PROJECTBASEDIR=%~dp0
@SET MAVEN_WRAPPER_JAR=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.jar
@SET MAVEN_WRAPPER_PROPERTIES=%MAVEN_PROJECTBASEDIR%.mvn\wrapper\maven-wrapper.properties
@SET DOWNLOAD_URL=https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.2.0/maven-wrapper-3.2.0.jar

@IF EXIST "%MAVEN_WRAPPER_JAR%" (
    @GOTO skipDownload
)

@ECHO Downloading Maven Wrapper jar...
powershell -Command "Invoke-WebRequest -Uri '%DOWNLOAD_URL%' -OutFile '%MAVEN_WRAPPER_JAR%'"

:skipDownload
@SET JAVA_EXEC=%JAVA_HOME%\bin\java.exe
@IF NOT EXIST "%JAVA_EXEC%" SET JAVA_EXEC=java

"%JAVA_EXEC%" -jar "%MAVEN_WRAPPER_JAR%" %*
