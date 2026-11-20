# SSAFESTA

가상 공간에서 사용자가 부스를 열고 프로젝트 전시, AI 상담, 설문을 운영하는 팀 프로젝트입니다. React 웹에서 부스를 관리하고, Unity WebGL 월드와 전용 서버가 실시간 이동을 처리합니다.

## 내가 맡은 일

인프라 담당으로 AWS EC2, Jenkins, Cloudflare를 운영했습니다. GitLab의 병합 전 검증과 Jenkins의 병합 후 빌드·배포를 연결했고, Nginx 설정과 Unity WebGL·게임 서버 배포 스크립트를 작성했습니다.

## 사용 기술

| 분야 | 기술 | 적용한 작업 |
| --- | --- | --- |
| 서버·네트워크 | AWS EC2, Linux, Docker Compose, Nginx, Cloudflare | Demo와 Production의 실행 환경을 나누고 DNS, TLS, 프록시와 캐시를 구성 |
| 검증·배포 | GitLab CI/CD, GitLab Runner, Jenkins, Bash | 병합 전 검증, 변경된 컴포넌트 배포, 운영 승격과 롤백 절차 구성 |
| 스토리지·데이터 연결 | Cloudflare R2, PostgreSQL, Redis | 백업은 R2에 보관하고, 백엔드 팀이 구성한 PostgreSQL·Redis를 각 실행 환경에 연결 |
| 모니터링·로그 | Prometheus, Grafana, Loki, Alloy | 지표와 로그를 수집하고 대시보드·알림 설정을 점검 |

**팀 애플리케이션 구성** · React, Spring Boot, FastAPI, Unity WebGL 및 Dedicated Server

## 배포 구조

한 대의 EC2에서 Demo와 Production을 운영했습니다. 팀은 Demo에서 배포 결과를 직접 확인한 뒤 승인했고, Production에는 같은 산출물을 재빌드 없이 승격했습니다. Demo에 현재 배포된 상태와 사람이 확인한 정상 상태를 따로 기록해 롤백할 때 정상 상태를 기준으로 삼았습니다.

## 코드 둘러보기

| 경로 | 내용 |
| --- | --- |
| [`infra/jenkins/`](infra/jenkins) · [`infra/deploy/`](infra/deploy) | Jenkins 파이프라인, 환경별 배포와 승격 |
| [`ci/`](ci) | 병합 전에 실행되는 검증 스크립트 |
| [`festa-frontend/`](festa-frontend) · [`backend/`](backend) · [`festa-ai/`](festa-ai) · [`festa-unity/`](festa-unity) | 웹, 백엔드, AI, Unity 팀 소스 |

[팀 시연 영상](https://youtu.be/dF9A3dqVsq4)

SSAFY GitLab에서 가져온 코드 스냅샷입니다. 운영 비밀값, 내부 협업 자료, 재배포 권한을 확인하지 못한 Unity 에셋과 빌드 산출물은 제외했습니다. Unity 장면을 실행하려면 원래 프로젝트의 에셋이 필요합니다.
