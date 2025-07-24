import * as path from "path";
import { defineConfig, UserConfig } from "rspress/config";
import { pluginFontOpenSans } from "rspress-plugin-font-open-sans";
import pluginSitemap from "rspress-plugin-sitemap";
import mermaid from "rspress-plugin-mermaid";
import directives from "rspress-plugin-directives";
import { pluginLlms } from "@rspress/plugin-llms";

const config: UserConfig = {
  root: path.join(__dirname, "docs"),
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
