import type { Meta, StoryObj } from "@storybook/react-vite";
import { MorpheLogoAnimated } from "@app/components/agents/MorpheLogoAnimated";

/**
 * Animated Morphe logo mark, used as a "thinking" indicator in the chat panel.
 */
const meta: Meta<typeof MorpheLogoAnimated> = {
  title: "Agents/MorpheLogoAnimated",
  component: MorpheLogoAnimated,
  parameters: { layout: "padded" },
  args: {
    size: 20,
  },
};
export default meta;
type Story = StoryObj<typeof meta>;

export const Default: Story = {};

export const Large: Story = {
  args: { size: 64 },
};
