# 将mineru源码上传到云服务器
地址：https://console.huaweicloud.com/ecm/?region=cn-south-1#/ecs/manager/vmList
点击ecs-minerU云服务器的远程登录按钮
点击CloudShell登录的立即登录按钮
输入密码，点击连接按钮
在本地将MinerU-mineru-4.0.11-released压缩成MinerU-mineru-4.0.11-released.zip文件
在云服务器左侧的资源管理器部分选择data文件夹，右键选择上传文件，将MinerU-mineru-4.0.11-released.zip上传到data文件夹下
使用“unzip MinerU-mineru-4.0.11-released.zip”命令解压MinerU-mineru-4.0.11-released.zip文件到data文件夹下，得到MinerU-mineru-4.0.11-released目录

# 构建镜像（在MinerU-mineru-4.0.11-released目录下执行）
docker build -t mineru-src:1.0.0 -f Dockerfile.src .

# 查看镜像是否构建成功
docker images | grep mineru-src

# 部署mineru-api服务
docker run -d --gpus all --shm-size 16g --ipc=host \
  --restart=always \
  -m 26g --memory-swap 26g \
  -e MINERU_PROCESSING_WINDOW_SIZE=16 \
  --health-cmd="curl -f http://localhost:8000/v1/health || exit 1" \
  --health-interval=30s \
  --health-timeout=10s \
  --health-retries=3 \
  --health-start-period=120s \
  -p 8000:8000 \
  --name mineru-api \
  mineru-src:1.0.0 \
  mineru-kit api-server \
    --host 0.0.0.0 --port 8000 \
    --tier standard \
    --preload-models \
    --concurrency 1 \
    --disable-image-analysis

# 查看mineru-api服务是否运行
docker ps | grep mineru-api

# 查看mineru-api服务日志
docker logs -f mineru-api