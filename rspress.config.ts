import * as path from "path";
import { defineConfig, UserConfig } from "rspress/config";
import { pluginFontOpenSans } from "rspress-plugin-font-open-sans";
import pluginSitemap from "rspress-plugin-sitemap";
import mermaid from "rspress-plugin-mermaid";
import directives from "rspress-plugin-directives";
import { pluginLlms } from "@rspress/plugin-llms";

const config: UserConfig = {
  root: path.join(__dirname, "docs"),
  route: {
    // 기본값은 .js/.jsx/.ts/.tsx 도 페이지로 잡는다. LangChain·Deep Agents 코스의
    // 실습 파일(practice.ts 등)이 페이지로 오인되어 브라우저 번들에 들어가면
    // node:fs 같은 내장 모듈 때문에 빌드가 깨진다. 이 사이트엔 커스텀 페이지가 없으므로
    // 마크다운만 페이지로 취급한다.
    extensions: [".md", ".mdx"],
    // 실습 프로젝트에서 npm install 하면 docs 아래에 node_modules 가 생긴다.
    // 그 안의 마크다운이 페이지로 잡히지 않도록 제외한다.
    exclude: ["**/node_modules/**"],
  },
  title: "Document",
  description: "Web Information",
  icon: "/favicon.png",
  logoText: "Main",
  themeConfig: {
    enableContentAnimation: true,
    lastUpdated: true,
    socialLinks: [{ icon: "github", mode: "link", content: "https://github.com/jl917" }],
    enableScrollToTop: true,
  },
  plugins: [
    pluginFontOpenSans(),
    pluginSitemap({ domain: "https://jl917.github.io" }),
    mermaid({ mermaidConfig: {} }),
    directives({
      directive: "giphy",
      transformer: {
        type: "globalComponent",
        getComponentName: (meta) => "Giphy",
        componentPath: path.join(__dirname, "./components/Giphy.tsx"),
      },
    }),
    pluginLlms(),
  ],
};

export default defineConfig(config);
