import * as path from "path";
import { defineConfig } from "rspress/config";
import { pluginFontOpenSans } from "rspress-plugin-font-open-sans";
import dd from "./plugins/datadog";
import clarity from "./plugins/msClarity"

export default defineConfig({
  root: path.join(__dirname, "docs"),
  title: "Document",
  description: "Web Information",
  icon: "/favicon.png",
  logoText: "Main",
  themeConfig: {
    enableContentAnimation: true,
    lastUpdated: true,
    socialLinks: [
      { icon: "github", mode: "link", content: "https://github.com/jl917" },
      {
        icon: "wechat",
        mode: "text",
        content: "wechat: _jl917",
      },
    ],
    enableScrollToTop: true,
  },
  plugins: [pluginFontOpenSans(), dd(), clarity()],
});
