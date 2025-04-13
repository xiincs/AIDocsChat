@echo off
echo ==================================
echo Tesseract训练数据下载工具
echo ==================================

REM 创建tessdata目录
if not exist tessdata mkdir tessdata
cd tessdata

echo 正在下载简体中文训练数据 (chi_sim.traineddata)...
curl -L -o chi_sim.traineddata https://github.com/tesseract-ocr/tessdata/raw/main/chi_sim.traineddata

echo 正在下载英文训练数据 (eng.traineddata)...
curl -L -o eng.traineddata https://github.com/tesseract-ocr/tessdata/raw/main/eng.traineddata

echo 下载完成！
echo 训练数据已保存至 %CD% 目录

cd ..
echo 按任意键退出...
pause > nul 