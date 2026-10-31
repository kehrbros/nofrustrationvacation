import { fireEvent, render, screen } from '@testing-library/react-native';

import { Button } from './Button';

describe('Button', () => {
  it('shows its label and reacts to presses', async () => {
    const onPress = jest.fn();
    await render(<Button label="Anmelden" onPress={onPress} />);

    await fireEvent.press(screen.getByRole('button', { name: 'Anmelden' }));

    expect(onPress).toHaveBeenCalledTimes(1);
  });

  it('ignores presses while loading', async () => {
    const onPress = jest.fn();
    await render(<Button label="Anmelden" loading onPress={onPress} />);

    await fireEvent.press(screen.getByRole('button'));

    expect(onPress).not.toHaveBeenCalled();
    expect(screen.queryByText('Anmelden')).toBeNull();
  });
});
