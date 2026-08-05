@rem Minimal Gradle startup script for Windows.
@echo off
setlocal
set APP_HOME=%~dp0
if defined JAVA_HOME goto javaHome
java -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
goto end
:javaHome
"%JAVA_HOME%\bin\java.exe" -classpath "%APP_HOME%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
:end
