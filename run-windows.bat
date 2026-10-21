@echo off
rem Runs Workshop Manager from the jar. Needs Java 21 (e.g. Temurin 21) installed.
rem The proper installer (MSI) bundles Java and needs nothing; see README.md.
start "" javaw -jar "%~dp0WorkshopManager-windows-x64-1.0.0.jar"
