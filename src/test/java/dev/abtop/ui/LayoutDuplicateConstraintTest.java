package dev.abtop.ui;

import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Layout;
import dev.tamboui.layout.Rect;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Reproducer for DuplicateConstraintException in TamboUI 0.1.0 cassowary solver.
 *
 * Constraint.min(0) always throws because the solver already adds an implicit
 * "size >= 0" constraint for each segment. Adding min(0) duplicates it.
 *
 * Stack trace:
 *   dev.tamboui.layout.cassowary.DuplicateConstraintException:
 *     Constraint already exists in solver: size_N(...) >= 0 [REQUIRED]
 *   at dev.tamboui.layout.cassowary.Solver.addConstraintInternal(...)
 *   at dev.tamboui.layout.Layout.split(Layout.java:208)
 */
@Disabled("min(0) bug fixed in TamboUI main — re-enable after upgrading past 0.1.0")
class LayoutDuplicateConstraintTest {

    /**
     * min(0) always throws — regardless of container size.
     */
    @Test
    void minZero_throws() {
        var area = new Rect(0, 0, 80, 20);

        var ex = assertThrows(Exception.class, () ->
            Layout.vertical()
                .constraints(Constraint.length(5), Constraint.min(0))
                .split(area)
        );
        assertTrue(ex.getClass().getSimpleName().contains("DuplicateConstraint"),
                "Expected DuplicateConstraintException, got: " + ex);
    }

    /**
     * min(1) works fine — no duplicate.
     */
    @Test
    void minOne_works() {
        var area = new Rect(0, 0, 80, 20);

        assertDoesNotThrow(() ->
            Layout.vertical()
                .constraints(Constraint.length(5), Constraint.min(1))
                .split(area)
        );
    }

    /**
     * Simplest possible reproducer: just min(0) alone.
     */
    @Test
    void singleMinZero_throws() {
        var area = new Rect(0, 0, 80, 10);

        var ex = assertThrows(Exception.class, () ->
            Layout.vertical()
                .constraints(Constraint.min(0))
                .split(area)
        );
        assertTrue(ex.getClass().getSimpleName().contains("DuplicateConstraint"),
                "Expected DuplicateConstraintException, got: " + ex);
    }

    /**
     * length(0) should be fine — it's not a min constraint.
     */
    @Test
    void lengthZero_works() {
        var area = new Rect(0, 0, 80, 10);

        assertDoesNotThrow(() ->
            Layout.vertical()
                .constraints(Constraint.length(5), Constraint.length(0))
                .split(area)
        );
    }

    /**
     * fill() as alternative to min(0) — should work.
     */
    @Test
    void fill_works() {
        var area = new Rect(0, 0, 80, 10);

        assertDoesNotThrow(() ->
            Layout.vertical()
                .constraints(Constraint.length(5), Constraint.fill())
                .split(area)
        );
    }

    /**
     * Horizontal direction has same bug.
     */
    @Test
    void horizontalMinZero_throws() {
        var area = new Rect(0, 0, 80, 10);

        var ex = assertThrows(Exception.class, () ->
            Layout.horizontal()
                .constraints(Constraint.length(20), Constraint.min(0))
                .split(area)
        );
        assertTrue(ex.getClass().getSimpleName().contains("DuplicateConstraint"),
                "Expected DuplicateConstraintException, got: " + ex);
    }
}
