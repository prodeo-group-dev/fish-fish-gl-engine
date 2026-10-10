-- A Company's currency comes from its tax jurisdiction (docs/GL_Cash_And_Bank_Books_SRS.md, decision D3,
-- Femi 2026-10-10): "every company has its tax jurisdiction; the currency in which that tax jurisdiction
-- operates is the primary cash and bank account currency".
--
-- Reference data, so adding a country is a data insert with no deploy. Nullable: a jurisdiction added
-- without a currency still works, the creator then names the currency.
--
-- Seed: UK GBP, IE EUR, NG NGN, SL SLE. Liberia, Guinea and Cote d'Ivoire are SLE by the standing
-- decision (2026-08-22) that this system recognises only SLE across the Mano River operation; their
-- own LRD, GNF and XOF are reference only and not onboarded (Femi confirmed 2026-10-10).
ALTER TABLE jurisdictions ADD COLUMN currency CHAR(3);

UPDATE jurisdictions SET currency = 'GBP' WHERE code = 'UK';
UPDATE jurisdictions SET currency = 'EUR' WHERE code = 'IE';
UPDATE jurisdictions SET currency = 'NGN' WHERE code = 'NG';
UPDATE jurisdictions SET currency = 'SLE' WHERE code IN ('SL', 'LR', 'GN', 'CI');
