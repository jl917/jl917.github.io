# Model Context Protocol (MCP)

MCP(Model Context Protocol)는 AI 애플리케이션을 외부 시스템에 연결하기 위한 오픈 소스 표준입니다.

## 사용사례

- 상담원은 사용자의 Google 캘린더와 Notion에 접근하여 더욱 개인화된 AI 비서 역할을 할 수 있습니다.
- Claude Code는 Figma 디자인을 사용하여 전체 웹 앱을 생성할 수 있습니다.
- 기업용 챗봇은 조직 전체의 여러 데이터베이스에 연결하여 사용자가 채팅을 통해 데이터를 분석할 수 있도록 지원합니다.
- AI 모델은 Blender에서 3D 디자인을 만들고 3D 프린터로 출력할 수 있습니다.

## 핵심 구성 요소

- 기본 프로토콜: Core JSON-RPC 메시지 유형
- 수명주기 관리: 연결 초기화, 기능 협상 및 세션 제어
- 권한 부여: HTTP 기반 전송을 위한 인증 및 권한 부여 프레임워크
- 서버 기능: 서버에서 제공하는 리소스, 프롬프트 및 도구
- 클라이언트 기능: 클라이언트에서 제공하는 샘플링 및 루트 디렉터리 목록
- 유틸리티: 로깅 및 인자 자동 완성과 같은 공통 관심사

## Architecture

https://modelcontextprotocol.io/specification/2025-11-25/architecture

## 추천 MCP

- filesystem
  - https://github.com/modelcontextprotocol/servers/tree/main/src/filesystem
- sequential thinking
  - https://github.com/modelcontextprotocol/servers/tree/main/src/sequentialthinking
- memory
  - https://github.com/modelcontextprotocol/servers/tree/main/src/memory
- github
  - https://github.com/github/github-mcp-server
- chrome devtool
  - https://github.com/ChromeDevTools/chrome-devtools-mcp
- context7
  - https://github.com/upstash/context7
