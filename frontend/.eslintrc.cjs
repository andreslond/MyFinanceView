module.exports = {
  root: true,
  env: { browser: true, es2022: true, node: true },
  extends: [
    'eslint:recommended',
    'plugin:@typescript-eslint/recommended'
  ],
  ignorePatterns: ['dist', 'node_modules', '.eslintrc.cjs', 'tailwind.config.cjs', 'postcss.config.cjs'],
  parser: '@typescript-eslint/parser',
  parserOptions: {
    ecmaVersion: 'latest',
    sourceType: 'module',
    ecmaFeatures: { jsx: true }
  },
  plugins: ['@typescript-eslint', 'react-hooks', 'react-refresh'],
  rules: {
    'react-refresh/only-export-components': ['warn', { allowConstantExport: true }],
    'react-hooks/rules-of-hooks': 'error',
    'react-hooks/exhaustive-deps': 'warn',
    '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }],
    // Isolation rule per design.md D3 + AGENTS.md.
    // @supabase/supabase-js may ONLY be imported from src/lib/supabaseClient.ts and src/services/**.
    'no-restricted-imports': ['error', {
      paths: [{
        name: '@supabase/supabase-js',
        message: 'Import the typed client from src/lib/supabaseClient.ts (or use a service in src/services/) instead. Components and hooks must NOT import supabase-js directly. See frontend/AGENTS.md.'
      }]
    }]
  },
  overrides: [
    {
      files: ['src/lib/supabaseClient.ts', 'src/services/**/*.ts'],
      rules: {
        'no-restricted-imports': 'off'
      }
    }
  ]
};
