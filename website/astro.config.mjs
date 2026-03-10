import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import tailwind from '@astrojs/tailwind';

export default defineConfig({
  site: 'https://aldefy.github.io',
  base: '/compose-rebound',
  integrations: [
    starlight({
      title: 'Rebound',
      social: [
        { icon: 'github', label: 'GitHub', href: 'https://github.com/aldefy/compose-rebound' },
        { icon: 'x.com', label: 'X', href: 'https://x.com/AditLal' },
      ],
      customCss: ['./src/styles/custom.css'],
      sidebar: [
        { label: 'Introduction', slug: 'intro' },
        {
          label: 'Getting Started',
          items: [
            { label: 'Installation', slug: 'getting-started/installation' },
            { label: 'Configuration', slug: 'getting-started/configuration' },
            { label: 'First Run', slug: 'getting-started/first-run' },
          ],
        },
        {
          label: 'Concepts',
          items: [
            { label: 'How It Works', slug: 'concepts/how-it-works' },
            { label: 'Budget Classes', slug: 'concepts/budget-classes' },
            { label: 'Violations', slug: 'concepts/violations' },
            { label: '$changed Bitmask', slug: 'concepts/changed-mask' },
            { label: 'Skip Tracking', slug: 'concepts/skip-tracking' },
            { label: 'Dynamic Scaling', slug: 'concepts/dynamic-scaling' },
          ],
        },
        {
          label: 'Advanced',
          items: [
            { label: 'Architecture', slug: 'advanced/architecture' },
            { label: 'Custom Budgets', slug: 'advanced/custom-budgets' },
            { label: 'Anonymous Lambdas', slug: 'advanced/anonymous-lambdas' },
            { label: 'Kotlin Versions', slug: 'advanced/kotlin-versions' },
            { label: 'KMP Support', slug: 'advanced/kmp-support' },
          ],
        },
        {
          label: 'CLI',
          items: [
            { label: 'Setup', slug: 'cli/setup' },
            { label: 'Commands', slug: 'cli/commands' },
          ],
        },
        {
          label: 'IDE Plugin',
          items: [
            { label: 'Installation', slug: 'ide-plugin/installation' },
            { label: 'Monitor Tab', slug: 'ide-plugin/monitor-tab' },
            { label: 'Hot Spots Tab', slug: 'ide-plugin/hotspots-tab' },
            { label: 'Timeline Tab', slug: 'ide-plugin/timeline-tab' },
            { label: 'Stability Tab', slug: 'ide-plugin/stability-tab' },
            { label: 'History Tab', slug: 'ide-plugin/history-tab' },
            { label: 'Editor Integration', slug: 'ide-plugin/editor-integration' },
            { label: 'Settings', slug: 'ide-plugin/settings' },
          ],
        },
        {
          label: 'Guides',
          items: [
            { label: 'Debugging Recompositions', slug: 'guides/debugging-recompositions' },
            { label: 'Fixing Violations', slug: 'guides/fixing-violations' },
            { label: 'CI Integration', slug: 'guides/ci-integration' },
          ],
        },
        {
          label: 'Integrations',
          items: [
            { label: 'AI Agent Skill', slug: 'integrations/ai-skill' },
          ],
        },
        { label: 'Roadmap', slug: 'roadmap' },
      ],
    }),
    tailwind({ applyBaseStyles: false }),
  ],
  output: 'static',
});
