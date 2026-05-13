-- ============================================================================
-- MyFinanceView — Seed Data
-- Database: Supabase PostgreSQL 15+
-- Schema: myfinance
-- Version: V003
--
-- Seeds:
--   - Default system categories (expense + income)
--   - Colombian banks (primary email-parsing targets)
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. Default System Categories (user_id = NULL)
-- ----------------------------------------------------------------------------

-- Expense categories
INSERT INTO myfinance.categories (user_id, name, type, icon, color) VALUES
    (NULL, 'Housing',          'expense', '🏠', '#4CAF50'),
    (NULL, 'Transportation',   'expense', '🚗', '#2196F3'),
    (NULL, 'Food & Groceries', 'expense', '🛒', '#FF9800'),
    (NULL, 'Dining Out',       'expense', '🍽️', '#E91E63'),
    (NULL, 'Utilities',        'expense', '💡', '#9C27B0'),
    (NULL, 'Healthcare',       'expense', '🏥', '#F44336'),
    (NULL, 'Insurance',        'expense', '🛡️', '#607D8B'),
    (NULL, 'Entertainment',    'expense', '🎬', '#FF5722'),
    (NULL, 'Shopping',         'expense', '🛍️', '#795548'),
    (NULL, 'Education',        'expense', '📚', '#3F51B5'),
    (NULL, 'Personal Care',    'expense', '💆', '#00BCD4'),
    (NULL, 'Subscriptions',    'expense', '📱', '#8BC34A'),
    (NULL, 'Debt Payments',    'expense', '💳', '#D32F2F'),
    (NULL, 'Other Expense',    'expense', '📦', '#9E9E9E');

-- Income categories
INSERT INTO myfinance.categories (user_id, name, type, icon, color) VALUES
    (NULL, 'Salary',           'income', '💰', '#2E7D32'),
    (NULL, 'Freelance',        'income', '💻', '#1565C0'),
    (NULL, 'Investments',      'income', '📈', '#E65100'),
    (NULL, 'Transfers In',     'income', '🔄', '#00838F'),
    (NULL, 'Other Income',     'income', '💵', '#757575');

-- ----------------------------------------------------------------------------
-- 2. Colombian Banks (primary email-parsing targets)
-- ----------------------------------------------------------------------------

INSERT INTO myfinance.banks (name, code) VALUES
    ('Bancolombia',            'bancolombia'),
    ('Davivienda',             'davivienda'),
    ('Banco de Bogotá',        'bogota'),
    ('BBVA Colombia',          'bbva'),
    ('Scotiabank Colpatria',   'colpatria'),
    ('Banco de Occidente',     'occidente'),
    ('Banco Popular',          'popular'),
    ('Nequi',                  'nequi'),
    ('Daviplata',              'daviplata'),
    ('Nu Colombia',            'nu');
