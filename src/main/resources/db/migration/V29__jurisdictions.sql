-- Jurisdiction registry (2026-10-05, direct instruction: jurisdiction is
-- reference DATA Prodeo can add to without a code change or deploy, e.g.
-- ZA, replacing the compiled `Jurisdiction` enum of 2026-09-19).
--
-- `enabled` is the governance gate: a jurisdiction is offered for
-- onboarding (GET /api/jurisdictions) and accepted by
-- POST /tenants/{tenantId}/companies only while enabled = TRUE. Adding a
-- country is an INSERT, not a deploy.
--
-- companies.jurisdiction and tax_rules.jurisdiction deliberately have NO
-- foreign key to this table: they already hold these same codes (V3/V5),
-- existing rows must keep loading even if a jurisdiction is later
-- disabled, and disabling must stop NEW onboarding without orphaning
-- existing Companies.
--
-- Seeded with the seven original jurisdictions (docs/UK, IE, NG, SL, LR,
-- GN, CI). UK is the single code for the whole United Kingdom, Northern
-- Ireland included; there is no GB or NI code.
CREATE TABLE jurisdictions (
    code CHAR(2) PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    enabled BOOLEAN NOT NULL
);

INSERT INTO jurisdictions (code, name, enabled) VALUES
    ('UK', 'United Kingdom (including Northern Ireland)', TRUE),
    ('IE', 'Ireland', TRUE),
    ('NG', 'Nigeria', TRUE),
    ('SL', 'Sierra Leone', TRUE),
    ('LR', 'Liberia', TRUE),
    ('GN', 'Guinea', TRUE),
    ('CI', 'Côte d''Ivoire', TRUE);
