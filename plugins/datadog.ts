import type { RspressPlugin } from "@rspress/shared";

export default function rspressPluginGoogleAnalytics(): RspressPlugin {
  return {
    name: "rspress-plugin-datadog-rum",
    config(config) {
      return config;
    },
    builderConfig: {
      html: {
        tags: [
          {
            tag: "link",
            head: true,
            append: false,
            attrs: {
              rel: "preconnect",
              href: "https://www.datadoghq-browser-agent.com",
            },
          },

          // {
          //   tag: "script",
          //   head: true,
          //   append: false,
          //   attrs: {
          //     async: true,
          //     src: "https://www.datadoghq-browser-agent.com/us5/v5/datadog-rum.js",
          //   },
          // },
          {
            tag: "script",
            head: false,
            append: false,
            children: `
            (function(h,o,u,n,d) {
              h=h[d]=h[d]||{q:[],onReady:function(c){h.q.push(c)}}
              d=o.createElement(u);d.async=1;d.src=n
              n=o.getElementsByTagName(u)[0];n.parentNode.insertBefore(d,n)
            })(window,document,'script','https://www.datadoghq-browser-agent.com/us5/v5/datadog-rum.js','DD_RUM')
            window.DD_RUM.onReady(function() {
              window.DD_RUM.init({
                clientToken: 'pub37215841d7358cc94633e1ccdea8b92e',
                applicationId: '86c2f4db-791c-46f5-b704-5033e16f28c5',
                site: 'us5.datadoghq.com',
                service: 'document',
                env: 'prod',
                // version: '1.0.0',
                sessionSampleRate: 100,
                sessionReplaySampleRate: 100,
                startSessionReplayRecordingManually: true,
                trackUserInteractions: true,
                trackResources: true,
                trackLongTasks: true,
                defaultPrivacyLevel: 'mask-user-input',
              });
            })
            `,
          },
        ],
      },
    },
  };
}
