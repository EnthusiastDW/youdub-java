# ============================================================
# YouDub Replica Frontend — Vite + React + Nginx Docker 镜像
# ============================================================
FROM node:22-alpine AS build
WORKDIR /app

COPY package.json ./
RUN npm install --ignore-scripts

COPY tsconfig.json tsconfig.app.json tsconfig.node.json vite.config.ts vitest.config.ts postcss.config.js ./
COPY index.html ./
COPY src/ src/

RUN npm run build

# ── Runtime (nginx) ────────────────────────────────────────
FROM nginx:alpine
WORKDIR /usr/share/nginx/html

RUN rm -rf ./*

COPY --from=build /app/dist/ ./

COPY <<'EOF' /etc/nginx/conf.d/default.conf
server {
    listen       3000;
    server_name  localhost;

    # 前端静态文件
    location / {
        root   /usr/share/nginx/html;
        index  index.html;
        try_files $uri $uri/ /index.html;
    }

    # API 反向代理到后端
    location /api/ {
        proxy_pass http://backend:8000;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_read_timeout 120s;
        proxy_send_timeout 120s;
    }

    # 大文件上传
    client_max_body_size 4G;
}
EOF

EXPOSE 3000

CMD ["nginx", "-g", "daemon off;"]
