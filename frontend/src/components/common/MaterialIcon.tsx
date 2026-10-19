import React from 'react';

interface Props {
  name: string;
  size?: number;
  color?: string;
  weight?: number;
  fill?: boolean;
  className?: string;
  style?: React.CSSProperties;
}

export const MaterialIcon: React.FC<Props> = ({
  name,
  size = 20,
  color,
  weight = 400,
  fill = false,
  className = '',
  style = {},
}) => {
  return (
    <span
      className={`material-symbols-outlined ${className}`}
      style={{
        fontSize: `${size}px`,
        width: `${size}px`,
        height: `${size}px`,
        lineHeight: 1,
        color: color || 'inherit',
        fontVariationSettings: `'FILL' ${fill ? 1 : 0}, 'wght' ${weight}, 'GRAD' 0, 'opsz' ${Math.min(Math.max(size, 20), 48)}`,
        display: 'inline-flex',
        alignItems: 'center',
        justifyContent: 'center',
        userSelect: 'none',
        verticalAlign: 'middle',
        ...style,
      }}
    >
      {name}
    </span>
  );
};

export default MaterialIcon;
