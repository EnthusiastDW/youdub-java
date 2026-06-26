import { describe, it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";
import { Progress } from "@/components/ui/progress";

describe("Button", () => {
  it("renders children", () => {
    render(<Button>Click me</Button>);
    expect(screen.getByRole("button", { name: "Click me" })).toBeInTheDocument();
  });

  it("applies variant classes", () => {
    render(<Button variant="destructive">Delete</Button>);
    const button = screen.getByRole("button", { name: "Delete" });
    expect(button.className).toContain("bg-destructive");
  });

  it("applies size classes", () => {
    render(<Button size="sm">Small</Button>);
    const button = screen.getByRole("button", { name: "Small" });
    expect(button.className).toContain("h-9");
  });

  it("supports disabled state", () => {
    render(<Button disabled>Disabled</Button>);
    expect(screen.getByRole("button", { name: "Disabled" })).toBeDisabled();
  });
});

describe("Badge", () => {
  it("renders children", () => {
    render(<Badge>Active</Badge>);
    expect(screen.getByText("Active")).toBeInTheDocument();
  });

  it("applies variant classes", () => {
    render(<Badge variant="success">Done</Badge>);
    const badge = screen.getByText("Done");
    expect(badge.className).toContain("bg-success");
  });
});

describe("Progress", () => {
  it("renders with default value", () => {
    render(<Progress />);
    const progress = screen.getByRole("progressbar");
    expect(progress).toBeInTheDocument();
  });

  it("sets aria-valuenow", () => {
    render(<Progress value={50} />);
    expect(screen.getByRole("progressbar")).toHaveAttribute("aria-valuenow", "50");
  });

  it("clamps values to 0-100", () => {
    render(<Progress value={150} />);
    const progress = screen.getByRole("progressbar");
    const inner = progress.querySelector("div");
    expect(inner?.getAttribute("style")).toContain("width: 100%");
  });
});
