@echo off
title Nexis Remote Control Server - Launcher
echo ===================================================
echo   NEXIS REMOTE CONTROL - INICIALIZADOR DE SERVIDOR
echo ===================================================
echo.

cd %~dp0

:: Verificar se o Python esta instalado
python --version >nul 2>&1
if %errorlevel% neq 0 goto :error_python

:: Criar ambiente virtual se nao existir
if exist .venv goto :venv_exists
echo [INFO] Criando ambiente virtual venv...
python -m venv .venv
if %errorlevel% neq 0 goto :error_venv
echo [OK] Ambiente virtual criado com sucesso.
echo.

:venv_exists
:: Ativar ambiente virtual
echo [INFO] Ativando ambiente virtual...
call .venv\Scripts\activate.bat

:: Instalar dependencias
echo [INFO] Instalando dependencias do requirements.txt...
python -m pip install --upgrade pip
python -m pip install -r backend/requirements.txt
if %errorlevel% neq 0 goto :error_pip
echo [OK] Dependencias instaladas.
echo.

:: Iniciar Servidor
echo [INFO] Iniciando servidor FastAPI em http://localhost:6061
echo.
echo ===================================================
echo   Pressione Ctrl+C para encerrar o servidor.
echo ===================================================
echo.
python backend/main.py
if %errorlevel% neq 0 goto :error_server
goto :eof

:error_python
echo [ERRO] Python nao foi encontrado no PATH do sistema.
echo Por favor, instale o Python e marque a opcao Add Python to PATH.
pause
exit /b

:error_venv
echo [ERRO] Falha ao criar o ambiente virtual.
pause
exit /b

:error_pip
echo [ERRO] Falha ao instalar dependencias do Python.
pause
exit /b

:error_server
echo.
echo [ERRO] O servidor parou de rodar com o erro %errorlevel%.
pause
exit /b
