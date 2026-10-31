import { CloseOutlined } from '@ant-design/icons';
import { Drawer } from 'antd';
import type { ReactNode } from 'react';

interface SheetProps {
  open: boolean;
  onClose: () => void;
  /** Small line above the title, e.g. the kind of incident. */
  eyebrow?: ReactNode;
  title: ReactNode;
  /** Shown left of the title, e.g. a warning icon. */
  icon?: ReactNode;
  children: ReactNode;
  /** Sticky area at the bottom, usually the main button. */
  footer?: ReactNode;
  /** Height on phones, e.g. "auto" or "85vh". */
  height?: string;
}

/** Bottom sheet as in the UI concept: modal dialog sliding up from the bottom, centered column on large screens. */
export default function Sheet({ open, onClose, eyebrow, title, icon, children, footer, height = 'auto' }: SheetProps) {
  return (
    <Drawer
      open={open}
      onClose={onClose}
      placement="bottom"
      height={height}
      closable={false}
      rootClassName="va-sheet"
      title={
        <div className="va-sheet-head">
          <div className="va-sheet-grip" aria-hidden="true" />
          <div className="va-sheet-title-row">
            {icon}
            <div className="va-sheet-titles">
              {eyebrow && <span className="va-eyebrow">{eyebrow}</span>}
              <h2 className="va-sheet-title">{title}</h2>
            </div>
            <button type="button" className="va-icon-button va-plain" aria-label="Schließen" onClick={onClose}>
              <CloseOutlined />
            </button>
          </div>
        </div>
      }
      footer={footer}
    >
      {children}
    </Drawer>
  );
}
