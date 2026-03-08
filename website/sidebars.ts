import type {SidebarsConfig} from '@docusaurus/plugin-content-docs';

const sidebars: SidebarsConfig = {
  docsSidebar: [
    'intro',
    {
      type: 'category',
      label: 'Getting Started',
      items: [
        'getting-started/installation',
        'getting-started/configuration',
        'getting-started/first-run',
      ],
    },
    {
      type: 'category',
      label: 'Concepts',
      items: [
        'concepts/budget-classes',
        'concepts/how-it-works',
        'concepts/violations',
        'concepts/skip-tracking',
        'concepts/changed-mask',
        'concepts/dynamic-scaling',
      ],
    },
    {
      type: 'category',
      label: 'IDE Plugin',
      items: [
        'ide-plugin/installation',
        'ide-plugin/monitor-tab',
        'ide-plugin/hotspots-tab',
        'ide-plugin/timeline-tab',
        'ide-plugin/stability-tab',
        'ide-plugin/history-tab',
        'ide-plugin/editor-integration',
        'ide-plugin/settings',
      ],
    },
    {
      type: 'category',
      label: 'CLI',
      items: [
        'cli/setup',
        'cli/commands',
      ],
    },
    {
      type: 'category',
      label: 'Advanced',
      items: [
        'advanced/architecture',
        'advanced/custom-budgets',
        'advanced/anonymous-lambdas',
        'advanced/kmp-support',
        'advanced/kotlin-versions',
      ],
    },
    {
      type: 'category',
      label: 'Guides',
      items: [
        'guides/debugging-recompositions',
        'guides/fixing-violations',
        'guides/ci-integration',
      ],
    },
    'roadmap',
  ],
};

export default sidebars;
