-- Apply after the connection lifecycle and product edit DDL, before deploying search.
-- Read only the current top-level identifier; archived identifiers are never a live link.
CREATE OR REPLACE FUNCTION sb_market_has_identifier(document text, identifier_key text)
RETURNS boolean
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    value jsonb;
BEGIN
    value := document::jsonb -> identifier_key;
    IF jsonb_typeof(value) NOT IN ('string', 'number') THEN
        RETURN false;
    END IF;
    RETURN COALESCE(length(btrim(value #>> '{}', E' \t\n\r\f')) > 0, false);
EXCEPTION WHEN invalid_text_representation THEN
    RETURN false;
END;
$$;

-- Exact top-level value comparison for current transmission evidence; no archived IDs or trimming.
CREATE OR REPLACE FUNCTION sb_market_identifier_equals(document text, identifier_key text, expected text)
RETURNS boolean
LANGUAGE plpgsql IMMUTABLE PARALLEL SAFE
AS $$
DECLARE
    value jsonb;
BEGIN
    value := document::jsonb -> identifier_key;
    IF jsonb_typeof(value) NOT IN ('string', 'number') THEN RETURN false; END IF;
    RETURN COALESCE(expected <> '' AND (value #>> '{}') = expected, false);
EXCEPTION WHEN invalid_text_representation THEN
    RETURN false;
END;
$$;
