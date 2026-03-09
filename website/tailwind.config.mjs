/** @type {import('tailwindcss').Config} */
export default {
  content: ['./src/**/*.{astro,html,js,jsx,md,mdx,ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        brand: {
          indigo: '#4F46E5',
          'indigo-light': '#6366F1',
          teal: '#14B8A6',
          'teal-light': '#2DD4BF',
          dark: '#0F0D1A',
          'dark-card': '#1A1730',
          'dark-border': '#2D2B42',
        },
      },
    },
  },
  plugins: [],
};
