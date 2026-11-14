import type { Config } from 'tailwindcss'

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontSize: { xs: ['14px', '20px'], sm: ['15px', '22px'] },
    },
  },
  plugins: [],
} satisfies Config