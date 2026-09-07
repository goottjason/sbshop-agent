export function ProductHtmlPreview({ html, label }: { html: string | null | undefined; label: string }) {
  if (!html) return <p className="pw-content-empty">상세 HTML 없음</p>;
  const srcDoc = `<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src https: http: data:; style-src 'unsafe-inline'; font-src 'none'; form-action 'none'; base-uri 'none'">${html}`;
  return <>
    <iframe title={`${label} 상세 HTML 미리보기`} sandbox="" referrerPolicy="no-referrer" srcDoc={srcDoc} className="pw-content-html" />
    <details className="pw-content-source"><summary>HTML 원문 보기 · {html.length.toLocaleString()}자</summary>
      <textarea aria-label={`${label} 상세 HTML 원문`} readOnly value={html} rows={8} spellCheck={false} />
    </details>
  </>;
}
