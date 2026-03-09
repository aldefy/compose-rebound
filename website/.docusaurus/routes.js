import React from 'react';
import ComponentCreator from '@docusaurus/ComponentCreator';

export default [
  {
    path: '/compose-rebound/blog',
    component: ComponentCreator('/compose-rebound/blog', 'b8c'),
    exact: true
  },
  {
    path: '/compose-rebound/blog/archive',
    component: ComponentCreator('/compose-rebound/blog/archive', 'ea9'),
    exact: true
  },
  {
    path: '/compose-rebound/blog/not-all-recompositions-are-equal',
    component: ComponentCreator('/compose-rebound/blog/not-all-recompositions-are-equal', 'd38'),
    exact: true
  },
  {
    path: '/compose-rebound/blog/tags',
    component: ComponentCreator('/compose-rebound/blog/tags', '910'),
    exact: true
  },
  {
    path: '/compose-rebound/blog/tags/compose',
    component: ComponentCreator('/compose-rebound/blog/tags/compose', '2bf'),
    exact: true
  },
  {
    path: '/compose-rebound/blog/tags/kotlin',
    component: ComponentCreator('/compose-rebound/blog/tags/kotlin', '7e9'),
    exact: true
  },
  {
    path: '/compose-rebound/blog/tags/performance',
    component: ComponentCreator('/compose-rebound/blog/tags/performance', 'a5c'),
    exact: true
  },
  {
    path: '/compose-rebound/blog/tags/recomposition',
    component: ComponentCreator('/compose-rebound/blog/tags/recomposition', '9f1'),
    exact: true
  },
  {
    path: '/compose-rebound/markdown-page',
    component: ComponentCreator('/compose-rebound/markdown-page', '0a5'),
    exact: true
  },
  {
    path: '/compose-rebound/docs',
    component: ComponentCreator('/compose-rebound/docs', '137'),
    routes: [
      {
        path: '/compose-rebound/docs',
        component: ComponentCreator('/compose-rebound/docs', 'b45'),
        routes: [
          {
            path: '/compose-rebound/docs',
            component: ComponentCreator('/compose-rebound/docs', 'a5c'),
            routes: [
              {
                path: '/compose-rebound/docs/advanced/anonymous-lambdas',
                component: ComponentCreator('/compose-rebound/docs/advanced/anonymous-lambdas', '93a'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/advanced/architecture',
                component: ComponentCreator('/compose-rebound/docs/advanced/architecture', '192'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/advanced/custom-budgets',
                component: ComponentCreator('/compose-rebound/docs/advanced/custom-budgets', '891'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/advanced/kmp-support',
                component: ComponentCreator('/compose-rebound/docs/advanced/kmp-support', 'c45'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/advanced/kotlin-versions',
                component: ComponentCreator('/compose-rebound/docs/advanced/kotlin-versions', 'f1e'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/cli/commands',
                component: ComponentCreator('/compose-rebound/docs/cli/commands', 'fb2'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/cli/setup',
                component: ComponentCreator('/compose-rebound/docs/cli/setup', 'fed'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/concepts/budget-classes',
                component: ComponentCreator('/compose-rebound/docs/concepts/budget-classes', '8a7'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/concepts/changed-mask',
                component: ComponentCreator('/compose-rebound/docs/concepts/changed-mask', '207'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/concepts/dynamic-scaling',
                component: ComponentCreator('/compose-rebound/docs/concepts/dynamic-scaling', '899'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/concepts/how-it-works',
                component: ComponentCreator('/compose-rebound/docs/concepts/how-it-works', 'f21'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/concepts/skip-tracking',
                component: ComponentCreator('/compose-rebound/docs/concepts/skip-tracking', '5dd'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/concepts/violations',
                component: ComponentCreator('/compose-rebound/docs/concepts/violations', '811'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/getting-started/configuration',
                component: ComponentCreator('/compose-rebound/docs/getting-started/configuration', '98a'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/getting-started/first-run',
                component: ComponentCreator('/compose-rebound/docs/getting-started/first-run', '5a6'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/getting-started/installation',
                component: ComponentCreator('/compose-rebound/docs/getting-started/installation', '22f'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/guides/ci-integration',
                component: ComponentCreator('/compose-rebound/docs/guides/ci-integration', '10d'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/guides/debugging-recompositions',
                component: ComponentCreator('/compose-rebound/docs/guides/debugging-recompositions', 'eea'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/guides/fixing-violations',
                component: ComponentCreator('/compose-rebound/docs/guides/fixing-violations', 'b10'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/ide-plugin/editor-integration',
                component: ComponentCreator('/compose-rebound/docs/ide-plugin/editor-integration', 'a1d'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/ide-plugin/history-tab',
                component: ComponentCreator('/compose-rebound/docs/ide-plugin/history-tab', '561'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/ide-plugin/hotspots-tab',
                component: ComponentCreator('/compose-rebound/docs/ide-plugin/hotspots-tab', '73a'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/ide-plugin/installation',
                component: ComponentCreator('/compose-rebound/docs/ide-plugin/installation', 'c1a'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/ide-plugin/monitor-tab',
                component: ComponentCreator('/compose-rebound/docs/ide-plugin/monitor-tab', '179'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/ide-plugin/settings',
                component: ComponentCreator('/compose-rebound/docs/ide-plugin/settings', '23e'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/ide-plugin/stability-tab',
                component: ComponentCreator('/compose-rebound/docs/ide-plugin/stability-tab', '01b'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/ide-plugin/timeline-tab',
                component: ComponentCreator('/compose-rebound/docs/ide-plugin/timeline-tab', 'daf'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/intro',
                component: ComponentCreator('/compose-rebound/docs/intro', '3b5'),
                exact: true,
                sidebar: "docsSidebar"
              },
              {
                path: '/compose-rebound/docs/roadmap',
                component: ComponentCreator('/compose-rebound/docs/roadmap', 'c2d'),
                exact: true,
                sidebar: "docsSidebar"
              }
            ]
          }
        ]
      }
    ]
  },
  {
    path: '/compose-rebound/',
    component: ComponentCreator('/compose-rebound/', '2bb'),
    exact: true
  },
  {
    path: '*',
    component: ComponentCreator('*'),
  },
];
