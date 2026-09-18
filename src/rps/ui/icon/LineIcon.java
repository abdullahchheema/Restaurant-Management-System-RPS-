package rps.ui.icon;

import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.GeneralPath;
import java.awt.geom.Line2D;
import java.awt.geom.RoundRectangle2D;

/**
 * A hand-drawn, single-stroke-weight icon family — deliberately not a stock icon set.
 * Every glyph draws on the same 20x20 optical grid with one 1.5px round-capped stroke,
 * no fills and no gradients, so the whole set reads as one considered family rather
 * than a mix of imported artwork. Color is supplied at draw time (muted at rest,
 * primary/accent when active) so a single glyph instance can be reused everywhere.
 */
public enum LineIcon {
    DINE_IN {
        @Override void draw(Graphics2D g) {
            // A plate: outer + inner concentric circle.
            g.draw(new Ellipse2D.Float(2, 2, 16, 16));
            g.draw(new Ellipse2D.Float(6, 6, 8, 8));
        }
    },
    TAKEAWAY {
        @Override void draw(Graphics2D g) {
            // A bag: trapezoid body + looped handle.
            GeneralPath body = new GeneralPath();
            body.moveTo(4, 7); body.lineTo(16, 7); body.lineTo(15, 18); body.lineTo(5, 18); body.closePath();
            g.draw(body);
            g.draw(new java.awt.geom.Arc2D.Float(6.5f, 2, 7, 8, 0, 180, java.awt.geom.Arc2D.OPEN));
        }
    },
    DELIVERY {
        @Override void draw(Graphics2D g) {
            // A scooter-ish delivery mark: two wheels + a connecting line.
            g.draw(new Ellipse2D.Float(2, 12, 6, 6));
            g.draw(new Ellipse2D.Float(12, 12, 6, 6));
            GeneralPath path = new GeneralPath();
            path.moveTo(5, 12); path.lineTo(5, 6); path.lineTo(11, 6); path.lineTo(15, 12);
            g.draw(path);
            g.draw(new Line2D.Float(8, 6, 8, 3));
        }
    },
    STATUS_COMPLETED {
        @Override void draw(Graphics2D g) {
            g.draw(new Ellipse2D.Float(2, 2, 16, 16));
            GeneralPath check = new GeneralPath();
            check.moveTo(6, 10.5f); check.lineTo(9, 13.5f); check.lineTo(14, 7);
            g.draw(check);
        }
    },
    STATUS_CANCELLED {
        @Override void draw(Graphics2D g) {
            g.draw(new Ellipse2D.Float(2, 2, 16, 16));
            g.draw(new Line2D.Float(7, 7, 13, 13));
            g.draw(new Line2D.Float(13, 7, 7, 13));
        }
    },
    STATUS_PENDING {
        @Override void draw(Graphics2D g) {
            // A clock face: circle + short hands, for "not settled yet".
            g.draw(new Ellipse2D.Float(2, 2, 16, 16));
            g.draw(new Line2D.Float(10, 10, 10, 5));
            g.draw(new Line2D.Float(10, 10, 14, 12));
        }
    },
    CATEGORY_PIZZA {
        @Override void draw(Graphics2D g) {
            GeneralPath slice = new GeneralPath();
            slice.moveTo(10, 2); slice.lineTo(17, 17); slice.lineTo(3, 17); slice.closePath();
            g.draw(slice);
            g.draw(new Ellipse2D.Float(8.5f, 7.5f, 1.6f, 1.6f));
            g.draw(new Ellipse2D.Float(11, 11, 1.6f, 1.6f));
        }
    },
    CATEGORY_SHAWARMA {
        @Override void draw(Graphics2D g) {
            GeneralPath cone = new GeneralPath();
            cone.moveTo(8, 2); cone.lineTo(13, 2); cone.curveTo(14, 9, 12, 15, 10.5f, 18);
            cone.curveTo(9, 15, 7, 9, 8, 2);
            g.draw(cone);
            g.draw(new Line2D.Float(10.5f, 2, 10.5f, 17));
        }
    },
    CATEGORY_BURGER {
        @Override void draw(Graphics2D g) {
            g.draw(new java.awt.geom.Arc2D.Float(2, 2, 16, 12, 0, 180, java.awt.geom.Arc2D.OPEN));
            g.draw(new Line2D.Float(2.5f, 11, 17.5f, 11));
            g.draw(new Line2D.Float(2.5f, 14, 17.5f, 14));
            g.draw(new RoundRectangle2D.Float(2.5f, 15.5f, 15, 2.5f, 2, 2));
        }
    },
    CATEGORY_FRIES {
        @Override void draw(Graphics2D g) {
            GeneralPath cup = new GeneralPath();
            cup.moveTo(5, 9); cup.lineTo(15, 9); cup.lineTo(13.5f, 18); cup.lineTo(6.5f, 18); cup.closePath();
            g.draw(cup);
            g.draw(new Line2D.Float(7, 9, 6, 3));
            g.draw(new Line2D.Float(10, 9, 10, 2));
            g.draw(new Line2D.Float(13, 9, 14, 3));
        }
    },
    CATEGORY_DRINKS {
        @Override void draw(Graphics2D g) {
            GeneralPath cup = new GeneralPath();
            cup.moveTo(5, 5); cup.lineTo(15, 5); cup.lineTo(13.5f, 18); cup.lineTo(6.5f, 18); cup.closePath();
            g.draw(cup);
            g.draw(new Line2D.Float(5, 5, 15, 5));
            g.draw(new Line2D.Float(9.5f, 2, 9.5f, 5));
        }
    },
    CATEGORY_DESSERTS {
        @Override void draw(Graphics2D g) {
            g.draw(new java.awt.geom.Arc2D.Float(3, 6, 14, 14, 20, 140, java.awt.geom.Arc2D.OPEN));
            g.draw(new Line2D.Float(10, 6, 10, 2));
            g.draw(new Ellipse2D.Float(9, 1, 2, 2));
        }
    },
    EDIT {
        @Override void draw(Graphics2D g) {
            GeneralPath pencil = new GeneralPath();
            pencil.moveTo(3, 17); pencil.lineTo(4, 13); pencil.lineTo(13.5f, 3.5f);
            pencil.lineTo(16.5f, 6.5f); pencil.lineTo(7, 16); pencil.closePath();
            g.draw(pencil);
            g.draw(new Line2D.Float(11.5f, 5.5f, 14.5f, 8.5f));
        }
    },
    DELETE {
        @Override void draw(Graphics2D g) {
            g.draw(new RoundRectangle2D.Float(4, 6, 12, 12, 2, 2));
            g.draw(new Line2D.Float(2, 6, 18, 6));
            g.draw(new Line2D.Float(7.5f, 3, 12.5f, 3));
            g.draw(new Line2D.Float(8, 9, 8, 15));
            g.draw(new Line2D.Float(12, 9, 12, 15));
        }
    },
    CONFIRM {
        @Override void draw(Graphics2D g) {
            GeneralPath check = new GeneralPath();
            check.moveTo(3, 10.5f); check.lineTo(8, 15.5f); check.lineTo(17, 4.5f);
            g.draw(check);
        }
    },
    PRINT {
        @Override void draw(Graphics2D g) {
            g.draw(new RoundRectangle2D.Float(3, 7, 14, 7, 1.5f, 1.5f));
            g.draw(new RoundRectangle2D.Float(5.5f, 2, 9, 5.5f, 1, 1));
            g.draw(new RoundRectangle2D.Float(5.5f, 13.5f, 9, 4.5f, 1, 1));
        }
    },
    LOGOUT {
        @Override void draw(Graphics2D g) {
            g.draw(new RoundRectangle2D.Float(3, 3, 8, 14, 2, 2));
            g.draw(new Line2D.Float(9, 10, 17, 10));
            GeneralPath arrow = new GeneralPath();
            arrow.moveTo(13.5f, 6.5f); arrow.lineTo(17, 10); arrow.lineTo(13.5f, 13.5f);
            g.draw(arrow);
        }
    },
    EYE_SHOW {
        @Override void draw(Graphics2D g) {
            GeneralPath lid = new GeneralPath();
            lid.moveTo(1.5f, 10); lid.curveTo(4, 5, 16, 5, 18.5f, 10);
            lid.curveTo(16, 15, 4, 15, 1.5f, 10);
            g.draw(lid);
            g.draw(new Ellipse2D.Float(8, 7.5f, 4, 5));
        }
    },
    EYE_HIDE {
        @Override void draw(Graphics2D g) {
            GeneralPath lid = new GeneralPath();
            lid.moveTo(1.5f, 10); lid.curveTo(4, 5, 16, 5, 18.5f, 10);
            lid.curveTo(16, 15, 4, 15, 1.5f, 10);
            g.draw(lid);
            g.draw(new Ellipse2D.Float(8, 7.5f, 4, 5));
            g.draw(new Line2D.Float(3, 16, 17, 4));
        }
    },
    PERSON {
        @Override void draw(Graphics2D g) {
            g.draw(new Ellipse2D.Float(6.5f, 2, 7, 7));
            GeneralPath shoulders = new GeneralPath();
            shoulders.moveTo(2.5f, 18);
            shoulders.curveTo(2.5f, 12.5f, 6.5f, 11, 10, 11);
            shoulders.curveTo(13.5f, 11, 17.5f, 12.5f, 17.5f, 18);
            g.draw(shoulders);
        }
    },
    PLUS {
        @Override void draw(Graphics2D g) {
            g.draw(new Line2D.Float(10, 3, 10, 17));
            g.draw(new Line2D.Float(3, 10, 17, 10));
        }
    };

    abstract void draw(Graphics2D g);

    /** Categories are free-text rows from the menu table, not a fixed enum — matched by
     *  name so the icon set stays coherent without forcing a schema change. Returns null
     *  for a category name outside the known set (e.g. a newly added one); callers fall
     *  back to a generic icon. Shared between every screen that needs a category-to-icon
     *  mapping (POS tiles/chips, the size picker) so the matching rules can't drift apart. */
    public static LineIcon forCategory(String name) {
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if (n.contains("pizza")) return CATEGORY_PIZZA;
        if (n.contains("shawarma") || n.contains("wrap") || n.contains("roll")) return CATEGORY_SHAWARMA;
        if (n.contains("burger")) return CATEGORY_BURGER;
        if (n.contains("fries") || n.contains("side")) return CATEGORY_FRIES;
        if (n.contains("drink") || n.contains("beverage")) return CATEGORY_DRINKS;
        if (n.contains("dessert") || n.contains("sweet")) return CATEGORY_DESSERTS;
        if (n.contains("broast")) return CATEGORY_BURGER;
        if (n.contains("deal")) return CATEGORY_PIZZA;
        return null;
    }

    /** Bound to a size and color at use — one glyph, reused with different tints for
     *  at-rest / active without needing separate icon assets per state. */
    public Icon of(int size, Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                Graphics2D g2 = (Graphics2D) g0.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                float scale = size / 20f;
                g2.scale(scale, scale);
                g2.setColor(color);
                g2.setStroke(new BasicStroke(1.5f / scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                draw(g2);
                g2.dispose();
            }

            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }
}
