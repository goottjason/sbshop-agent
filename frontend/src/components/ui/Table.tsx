import React from 'react';

// fluid=true면 컨테이너가 부모 폭을 100% 채운다(전폭 데이터테이블용). 기본값(false)은
// inline-block으로 콘텐츠 폭에 맞춘다 — OrderGrid 등 가로스크롤·frozen 컬럼 화면 하위호환.
// minTableWidth: fluid일 때 컨테이너 최소 폭(px). 좁은 화면에선 이 값이 유지돼 바깥
// overflow 컨테이너에서 가로 스크롤이 나므로 컬럼이 찌그러지지 않는다.
export const Table = React.forwardRef<
  HTMLTableElement,
  React.HTMLAttributes<HTMLTableElement> & { fluid?: boolean; minTableWidth?: number }
>(
  ({ fluid, minTableWidth, ...props }, ref) => (
    <div className="table-container" style={{
      display: fluid ? 'block' : 'inline-block',
      width: fluid ? '100%' : undefined,
      minWidth: fluid ? minTableWidth : undefined,
      overflow: fluid ? 'hidden' : undefined,
      border: '1px solid #e5e7eb', borderRadius: '8px',
      boxShadow: '0 4px 6px -1px rgba(0, 0, 0, 0.05), 0 2px 4px -1px rgba(0, 0, 0, 0.03)',
    }}>
      <table
        ref={ref}
        {...props}
        style={{ width: '100%', borderCollapse: 'separate', borderSpacing: 0, textAlign: 'left', fontSize: '12px', ...props.style }}
      />
    </div>
  )
);
Table.displayName = "Table";

export const TableHeader = React.forwardRef<HTMLTableSectionElement, React.HTMLAttributes<HTMLTableSectionElement>>(
  (props, ref) => (
    <thead ref={ref} {...props} style={{ backgroundColor: '#f9fafb', position: 'sticky', top: 0, zIndex: 20, boxShadow: '0 1px 2px 0 rgba(0, 0, 0, 0.05)', ...props.style }} />
  )
);
TableHeader.displayName = "TableHeader";

export const TableBody = React.forwardRef<HTMLTableSectionElement, React.HTMLAttributes<HTMLTableSectionElement>>(
  (props, ref) => (
    <tbody ref={ref} {...props} style={{ ...props.style }} />
  )
);
TableBody.displayName = "TableBody";

export const TableRow = React.forwardRef<HTMLTableRowElement, React.HTMLAttributes<HTMLTableRowElement>>(
  (props, ref) => (
    <tr
      ref={ref}
      onMouseEnter={(e) => (e.currentTarget.style.backgroundColor = '#f3f4f6')}
      onMouseLeave={(e) => (e.currentTarget.style.backgroundColor = 'transparent')}
      {...props}
      style={{ borderBottom: '1px solid #e5e7eb', transition: 'background-color 0.2s ease', ...props.style }}
    />
  )
);
TableRow.displayName = "TableRow";

export const TableHead = React.forwardRef<HTMLTableCellElement, React.ThHTMLAttributes<HTMLTableCellElement>>(
  (props, ref) => (
    <th
      ref={ref}
      {...props}
      style={{ padding: '8px 12px', fontWeight: 400, color: '#374151', fontSize: '12px', borderBottom: '2px solid #d1d5db', whiteSpace: 'nowrap', textAlign: 'center', ...props.style }}
    />
  )
);
TableHead.displayName = "TableHead";

export const TableCell = React.forwardRef<HTMLTableCellElement, React.TdHTMLAttributes<HTMLTableCellElement>>(
  (props, ref) => (
    <td
      ref={ref}
      {...props}
      style={{ padding: '8px 12px', color: '#111827', fontSize: '12px', verticalAlign: 'middle', borderBottom: '1px solid #e5e7eb', whiteSpace: 'nowrap', textAlign: 'center', ...props.style }}
    />
  )
);
TableCell.displayName = "TableCell";
