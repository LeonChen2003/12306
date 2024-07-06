@echo off
chcp 65001
REM 导航到文件所在目录
cd /d %~dp0

set JDK_HOME=jdk\bin
cd %JDK_HOME%

java -version

set SERVICE_HOME=../../services
set VUE_HOME=../../console-vue

REM 启动后端服务
start /B java -jar %SERVICE_HOME%/gateway-service/target/index12306-gateway-service.jar
start /B java -jar %SERVICE_HOME%/order-service/target/index12306-order-service.jar
start /B java -jar %SERVICE_HOME%/pay-service/target/index12306-pay-service.jar
start /B java -jar %SERVICE_HOME%/ticket-service/target/index12306-ticket-service.jar
start /B java -jar %SERVICE_HOME%/user-service/target/index12306-user-service.jar
echo 后端服务启动中,前端项目待部署

pause

REM 启动前端服务
cd %VUE_HOME%
start /B yarn serve
echo 前端服务中

pause
